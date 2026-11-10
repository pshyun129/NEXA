# migration-service (이관)

기존 시스템·엑셀의 인사 데이터를 올려서 검증·정리한 뒤 각 서비스로 적재하는 서비스.

> 포트(8087)와 DB 이름(`nexa_migration`)은 팀 합의 전 가정값이다.

## 흐름

```text
작업 생성 → 파일 업로드(읽기·매핑 추천·검증 자동) → 매핑 수정 / 자동 정리 / 행 직접 수정
→ 기준정보 확인 → 미리보기(dryRun) → 적재(기준정보 → 조직 → 직원 → 직급별 기본급 → 지난 급여명세서) → 대사 → 필요하면 묶음 단위 되돌리기
```

## 단독 실행

```bash
# 1) 이관용 Postgres (팀 compose의 postgres를 써도 됨)
docker run -d --name migration-pg-local -e POSTGRES_DB=nexa_migration \
  -e POSTGRES_USER=nexa_migration_app -e POSTGRES_PASSWORD=localpw -p 127.0.0.1:5434:5432 postgres:17-alpine

# 2) 실행 (Eureka·Config 없이)
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/nexa_migration \
MIGRATION_DB_PASSWORD=localpw \
./gradlew bootRun --args='--spring.profiles.active=local'
```

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `SERVER_PORT` | 8087 | |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/nexa_migration` | |
| `MIGRATION_DB_PASSWORD` | (없음) | |
| `HR_SERVICE_URL` | `http://localhost:8081` | user-service 이관용 내부 API 주소 |
| `PAY_SERVICE_URL` | `http://localhost:8085` | payroll-service 이관용 내부 API 주소 |
| `JWT_JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | 토큰 검증용 user-service 공개키 주소. 이관 API는 관리자(ADMIN) 토큰만 받음 |
| `MIGRATION_PERMIT_URLS` | `/actuator/health,/error` | 토큰 서버 없이 로컬 평가할 때만 `,/api/v1/admin/migrations/**`를 더해 연다. 배포에서는 쓰지 말 것 |
| `GMS_KEY` | (없음) | 매핑 추천 LLM 키. 없으면 동의어 추천만 씀. 저장소·설정 파일에 넣지 말 것 |
| `MIGRATION_LLM_BASE_URL` | `https://gms.ssafy.io/gmsapi/api.openai.com/v1` | OpenAI Responses API 형식이면 바꿔 끼울 수 있음 (팀 vLLM 등) |
| `MIGRATION_LLM_MODEL` | `gpt-6-luna` | |

## 테스트

```bash
./gradlew test   # Docker 필요 (Testcontainers로 Postgres 17을 띄움)
```

시연·리허설 순서와 숫자는 [`tools/datagen/demo/README.md`](tools/datagen/demo/README.md)에 있다.

적재 테스트는 `FakeHrServer`(실제 HTTP 가짜 서버, 인사·급여 이관 API를 함께 흉내)로 확인한다. user-service(`/internal/v1/hr/migration`)·
payroll-service(`/internal/v1/pay/migration`)의 실제 이관 API와는 시연 파일로 로컬에서 끝까지 확인했다 (`tools/datagen/demo/README.md`).

사원의 부서 값은 같은 작업 조직 파일의 부서코드로 바꿔 보낸다. user-service는 부서를 코드로 먼저 찾고 이름은 하나뿐일 때만 쓰므로,
같은 이름의 기존 부서가 있어도 새로 넣은 부서로 들어간다.

직위·직급·직책·직군>직종>직무는 회사마다 체계가 달라서, 사원 파일에 나온 값을 모아 기준정보 묶음(`HR_CODE`, 사원 파일 이름으로)을
사원보다 먼저 넣는다. user-service는 없는 값만 만들고 있는 값은 그대로 둔다 (서열도 바꾸지 않음).
- 직위 서열: 사원·주임·대리·과장·차장·부장·이사·상무·전무·부사장·사장·대표이사 순으로 매기고, 모르는 직위는 그 뒤에 나온 순서대로
- 직급: NEXA 직급은 호봉이 꼭 있어 항상 이름 + 호봉으로 보낸다 (`GradeResolver`)
  - `G3 2호봉`·`G3-2`는 이름 G3 + 호봉 2. 호봉 열이 따로 있으면 합친다 (`2급` + 27 → `2급 27호봉`)
  - 직급 열이 없고 직위가 있으면 직위를 직급으로 쓴다 (`GRADE_FROM_POSITION`, 필수 열 누락으로 막지 않음)
  - 호봉을 모르면 그 직급의 알려진 호봉(대상 + 같은 파일)이 없을 때 1호봉, 하나면 그 호봉 (`GRADE_STEP_DEFAULTED`).
    여럿이면 하나로 못 고르므로 적재를 막는다 (`LOAD_009`, `GET /{id}/references`에서 `AMBIGUOUS`, 미리보기 `ambiguousReferences` > 0 이면 `loadable=false`).
    기준정보 확인은 적재할 사원 파일만 본다 (같은 작업에 사원 파일을 다시 올렸으면 가장 최근 파일)
- 되돌리기는 사원 → 조직 → 기준정보 순. 사원 파일 하나에서 묶음이 둘(기준정보·사원) 나오고 대사도 묶음마다 한 줄

지난 급여(`PAY_RESULT_HISTORY`, 급여대장 한 행 = 한 사람의 한 달)는 사원 다음에 payroll로 넣는다.
- 사번을 user-service에서 찾아(`employees/lookup`) 사원 ID와 명세서용 이름·부서명·직위명·입사일을 붙인다. 귀속월은 귀속월 열, 없으면 시트·파일 이름(2026-11)
- 금액 필드는 payroll 급여 항목 코드와 1:1 (기본급 → `BASE_PAY` …). 매핑되지 않은 지급·공제 열은 지급계·공제계와의 차이로 기타수당·기타공제가 되어 항목 합 = 합계
- 그 앞에 직급별 기본급 묶음(`PAY_PROFILE`, 급여 파일 이름으로): 가장 최근 달 그 직급 사람들 기본급의 최빈값. payroll은 없는 직급만 만든다
- 지난 명세서는 계산하지 않고 급여대장 값 그대로 저장한다. 근태 시간은 연장근로시간만 옮기고 나머지는 0
- 적재하는 급여 파일에서 직원명부에 없는 사번은 적재 불가 (참고 자료로 올리면 경고)
- 되돌리기는 급여명세서 → 직급별 기본급 → 사원 순

## API (`/api/v1/admin/migrations`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/` | 작업 생성 |
| GET | `/?page=&size=` | 작업 목록 (최근 만든 것부터, 파일 수·적재된 묶음 수) |
| GET | `/{id}` | 작업 상세 (파일, 적재 묶음) |
| POST | `/{id}/files?entity=&sheet=` | 업로드 → 읽기·매핑 추천·검증. `sheet` 없으면 entity 필드와 가장 잘 맞는 시트 |
| GET | `/{id}/files/{fileId}` | 파일 진단, 매핑, 오류 건수 |
| GET | `/{id}/files/{fileId}/formulas` | 엑셀 수식에서 뽑은 열별 규칙, 규칙에서 벗어난 행, 같은 양식 다른 시트와 비교 |
| GET | `/{id}/files/{fileId}/rules/summary?refresh=` | 수식 규칙을 사람 말로 정리 (LLM, 열 이름·대표 수식·예외 개수만 보냄, 셀 값 없음). 저장해 두고 `refresh=true`일 때만 다시 만듦 |
| PUT | `/{id}/files/{fileId}/mappings` | 매핑 확정 → 재검증 |
| PATCH | `/{id}/files/{fileId}/rows/{rowNo}` | 행 직접 수정 → 재검증. 날짜·숫자·이메일·허용값·길이 형식이 틀린 값은 저장하지 않고 400 |
| GET | `/{id}/issues` | 오류: 심각도별 건수, 원인별 묶음, 행 단위 목록 |
| POST | `/{id}/fixes` | 자동 정리 일괄 적용. `issueTotals`(작업 전체) + `fileTotals`(fileId를 줬을 때 그 파일) |
| GET | `/{id}/references` | 사원 파일에서 뽑은 기준정보와 대상 비교: `NEW`(새로 만듦) / `EXISTS`(있는 값 씀) / `AMBIGUOUS`(적재 막힘) |
| POST | `/{id}/loads?dryRun=` | 적재 / 미리보기 (차단·자동정리·고를 수 없는 기준정보 0건일 때만). 미리보기에 기준정보 묶음도 나옴. fileIds가 없으면 조직·사원은 가장 최근 파일만, 지난 급여는 적재 안 된 파일 모두 |
| DELETE | `/{id}/batches/{batchId}` | 묶음 되돌리기 |
| GET | `/{id}/reconciliation` | 대사 |

## 매핑 추천

- 동의어 사전(필드 이름·한글 라벨·동의어)으로 먼저 연결하고, 못 찾은 컬럼만 LLM에게 묻는다 (`LlmMappingAdvisor`)
- LLM에게 보내는 것: 컬럼 이름, 값의 모양(숫자 → 9, 한글 → 가, 영문 → a, 숫자 바로 뒤 년·월·일·원만 유지), 대상 필드 목록. 실제 값은 보내지 않음
- 응답은 보낸 컬럼·스키마에 있는 필드·아직 안 쓴 필드만, 한 필드 한 컬럼으로 걸러 받는다. 실패·키 없음이면 동의어 추천만
- 매핑 응답의 `suggestedBy`(SYNONYM·LLM·USER)와 `reason`(LLM 근거)으로 화면에서 구분. 추천은 확정이 아니라 사람이 확인
- 평가(가상 회사 7곳): 동의어만 59/74 → LLM 추가 73/74. 기존 3곳은 그대로 맞음
- 허용 값 목록이 있는 필드(재직상태·직원구분 …)에 목록에 없는 값(`Y`, `촉탁직`)이 있으면 LLM에게 어느 허용 값인지 묻는다 (`EnumValueSuggester`)
  - 코드성 값만 보낸다: 서로 다른 값 30개 이하, 값마다 20자 이하. 파일·필드·값마다 한 번만 묻고 `value_suggestion`(V7)에 저장
  - 추천이 있으면 차단(`INVALID_ENUM`) 대신 자동 정리 제안(`ENUM_VALUE_SUGGESTED`, FIX)으로 내고, 사람이 적용해야 값이 바뀐다
  - LLM이 꺼져 있거나 답이 없어도, 값이 허용 값 하나의 앞부분이면(`정규` → `정규직`, `파트` → `파트타임`, 두 글자 이상) 같은 코드로 제안한다

## 엑셀 읽기·검사

- 모든 시트를 읽는다. 응답의 `sheets`에 시트별 행 수·헤더 행·수식 셀 수, `sheetName`에 읽은 시트
- 헤더 행: 위쪽 30행에서 글자 칸이 가장 많은 행의 절반 이상인 첫 행 (제목·귀속월·요율 행 건너뜀). 세로 병합은 채우고, 가로 병합 묶음 머리글은 이름이 겹치는 열에만 붙임 (`07월 연장`)
- 합계·소계 행은 데이터에서 빼고 `TOTAL_ROW_SKIPPED`, 합계 아래 데이터 행은 `ROW_AFTER_TOTAL`. 소계로 보는 행: 앞쪽 칸이 `합계`·`소계`·`강남점 소계`처럼 끝나는 행, 수식 칸 대부분이 같은 열 위쪽 행을 더하는 행(`=SUM(F4:F25)`)
- 수식 셀은 저장된 계산값을 값으로 쓰고, 수식 문자열·오류값은 `staging_row.meta`에 따로 저장. 오류값 셀은 `FORMULA_ERROR`(차단)
- 수식 규칙: 열마다 대표 수식(`=ROUNDDOWN(({기본급}+{직책수당})/설정!$B$9,0)`), 값 직접 입력 `HARDCODED_VALUE`, 빈 칸 `BLANK_IN_FORMULA_COLUMN`,
  다른 모양 `FORMULA_INCONSISTENT`, 같은 파일·같은 헤더의 다른 시트와 대표 수식이 다름 `FORMULA_DIFFERS_ACROSS_SHEETS` (모두 경고)
  참조하는 시트만 다른 행(총괄 시트의 부서별 행 `='가공1팀'!U71`, 사람마다 소속 시트를 찾는 SUMIFS)은 같은 규칙으로 봄
- 검산 (모두 경고, 계산값은 `suggestion`에):
  - 대표 수식이 같은 행 칸의 덧셈·뺄셈·SUM(`=SUM({기본급}:{식대},{연장수당}:{휴일수당})`, `={발생일수}-{사용일수}`)이면 행마다 다시 계산해 저장값과 비교 `RECALC_MISMATCH`.
    차이가 항목 한 열의 값과 같으면 그 열을 알려 줌 (SUM 범위 누락·숫자로 바꿔 넣은 합계)
  - 수식이 없는 파일(CSV·값만 붙여 넣은 엑셀): 열 이름으로 지급계·공제계·실지급을 찾아 `지급계 = 왼쪽 지급 항목 합`, `공제계 = 지급계와 공제계 사이 공제 항목 합`,
    `실지급 = 지급계 − 공제계`로 추정해 검산. 대부분 행이 안 맞고 차이가 한 항목으로 설명되지 않으면 추정이 틀린 것으로 보고 한 건으로 묶음
  - 지급계·공제계 수식 범위에 들어가지 않은 지급·공제 항목 열 `TOTAL_ITEM_MISSING` (시트 단위)
  - ROUND·VLOOKUP 등이 섞인 수식(통상시급, 4대보험, 소득세)은 아직 다시 계산하지 않음
- 급여 법정 기준 비교 (모두 경고, 수식이 아니라 값으로 보므로 설정 시트 참조·CSV에도 동작). 귀속연도는 시트 이름 → 파일 이름(`2026-07`)에서 찾고, 기준표는 `StatutoryRates`(2023~2026)
  - 4대보험: 열마다 (기준 금액, 요율) 조합 중 가장 많은 행을 설명하는 것을 찾아(60% 이상) 귀속연도 요율과 다르면 `INSURANCE_RATE_OUTDATED` (시트 단위).
    기준 금액이 비과세 식대를 포함한 지급계이면 `INSURANCE_BASE_INCLUDES_MEAL`
    기준 금액 후보: 보수월액·기준소득월액 열, 과세대상, 지급계 − 비과세 식대, 지급계. 장기요양은 '건강보험료 × 비율' 방식만 인식하고,
    어느 조합도 60%를 못 넘으면(상한에 걸린 고소득자가 많은 시트 등) 아무 말도 하지 않음 — 경고가 없다고 맞는 것은 아님
  - 가산수당: 통상시급 × 시간 × 법정 배율(연장 1.5, 야간 0.5, 휴일 8시간까지 1.5·초과 2.0)보다 적은 행 `PREMIUM_BELOW_STATUTORY`.
    휴일 8시간 초과분을 대부분 1.5배로 계산했으면 시트 단위 한 건. 상시 5인 미만 사업장은 적용 제외라 경고로만 둠
  - 최저임금: 지급계 왼쪽 지급 항목 중 가산수당을 뺀 합이 귀속연도 최저시급 × 209시간보다 적은 행 `BELOW_MINIMUM_WAGE`.
    30% 넘게 걸리면 단시간 근로자·월 중 입사자 자료로 보고 한 건으로 묶음. 귀속연도를 모르면 하지 않음
- 직원명부 대조: 직원명부가 아닌 파일의 사번이 같은 작업의 직원명부에 있는지 `EMP_NOT_IN_ROSTER`(적재하는 파일이면 적재 불가), 이름이 같은지 `EMP_NAME_MISMATCH`,
  사번 빈 칸 `EMP_KEY_MISSING`(이름으로 찾은 사번 제안). 숫자 셀이라 앞자리 0이 빠진 사번은 명부 자리수로 채워 대조하고 `EMPNO_ZERO_PADDED`
- 직원명부 날짜 대조 (경고): 다른 파일의 입사일 ≠ 명부 입사일 `ROSTER_HIRE_DATE_MISMATCH`, 4대보험 취득일 ≠ 입사일 `INSURANCE_ACQ_DATE_MISMATCH`(보험별 열은 한 건으로),
  상실일 ≠ 퇴사일 다음 날·퇴사자인데 상실일 없음 `INSURANCE_LOSS_DATE_MISMATCH`. 명부에 사번이 중복이거나 퇴사일 < 입사일(명부 오류)이면 그 날짜는 쓰지 않고,
  명부에 퇴사일 열이 없으면 상실일은 보지 않음
- 코드값 오타: 직위·직급 등에서 드문 값이 흔한 값과 한글 자모 하나만 다르면 `CODE_TYPO_SUSPECTED` + 제안값 (숫자만 다른 값 — G1 1호봉 ↔ G5 1호봉, 영업1팀 ↔ 영업2팀 — 은 다른 코드로 본다)
- 부양가족 (경고): 기본공제 표시된 자녀 나이 > 20·부모 나이 < 60 `DEPENDENT_AGE_RULE`, 원천징수비율이 80·100·120%가 아님 `WITHHOLDING_RATIO_INVALID`,
  공제가족수가 같은 작업의 가장 늦은 달 급여 시트(이름에 연월이 있는 파일)의 공제가족수와 다름 `FAMILY_COUNT_MISMATCH`.
  가족수 대조는 이름에 연월이 없는 파일(부양가족 집계) 쪽에만 내고, 급여 시트를 나중에 올리면 그 파일을 다시 검증
- 급여 행 (경고): 실지급액 음수 `NEGATIVE_NET_PAY`, 귀속월 열이 있는 급여(여러 달이 세로로 쌓인 파일)에서 사람마다 빠진 달 `PAY_MONTH_GAP`,
  귀속월(행의 귀속월 열, 없으면 시트·파일 이름)이 직원명부 재직 기간(입사 달 ~ 퇴사 달) 밖인데 금액이 있는 행 `PAID_OUTSIDE_EMPLOYMENT`
- 직급별 기본급 (경고, 지난 급여 파일): 가장 최근 달에서 직원명부 직급이 같은데 기본급이 그 직급의 최빈값과 다른 행 `PAY_GRADE_BASE_VARIES`.
  적재하면 직급별 기본급은 최빈값으로 만들어진다. 30% 넘게 다르면(연봉제) 한 건으로 묶음
- 연봉이력 (경고): 사람·연도별 행(연도 + 연봉 열) 또는 연도별 열(`SAL_2026`, `연봉(2026)`)을 읽어
  - 인상률을 비율(0.035)이 아니라 %(3.5)로 적은 칸 `RAISE_RATE_AS_PERCENT` (같은 열 값의 80% 이상이 1 미만일 때만)
  - 급여 시트 연도의 월 기본급(없으면 연봉 ÷ 12) ≠ 가장 늦은 달 급여 시트 기본급 `SALARY_PAYROLL_MISMATCH` (100원·1,000원 허용)
  - 다른 파일의 연도 붙은 연봉 열(4대보험 `연봉(2026)`) ≠ 연봉이력 그 해 연봉 `SALARY_HISTORY_MISMATCH` (어느 쪽이 맞는지는 판단하지 않음)
  - 급여 시트·연봉이력을 어떤 순서로 올려도 대조되게 짝이 되는 파일을 다시 검증
- 조직 부서유형·부서장 (user-service 부서 유형·부서장 지정 규칙과 같게): 최상위만 회사, 하위가 상위보다 높은 단계 `DEPT_TYPE_ORDER`.
  부서장 사번을 같은 작업의 직원명부와 대조해 명부에 없음·재직 아님·그 부서나 하위 부서 소속 아님을 경고. 부서장이 비어 있으면 결재선 자동 결정이 안 되므로
  user-service V4와 같은 규칙(부서 유형에 맞는 직책 대표이사·본부장·팀장인 그 부서 재직자가 한 명)으로 사번을 제안하고, 못 정하면 공석 경고. 직원명부를 나중에 올리면 조직 파일을 다시 검증
- 사원 부서 (적재 불가): 부서 값이 같은 작업의 가장 최근 조직 파일 부서코드·부서명에 없으면 `DEPT_NOT_IN_ORG`. 조직 파일이 없으면 파일 단위 경고 `ORG_FILE_MISSING`,
  조직 파일을 나중에 올리면 사원 파일을 다시 검증. 회사 전체를 옮기는 이관이라 대상에만 있는 부서도 조직 파일에 넣어야 한다
- `REFERENCE` entity: 적재하지 않는 참고 자료(근태기록·발령대장 등)를 올려 위 검사·규칙 추출만 한다

## 이슈 코드

심각도: **적재 불가**(BLOCK, 고치기 전에는 적재 안 됨) / **확인 필요**(WARN) / **자동 정리**(FIX, `suggestion` 값으로 바꾸자는 제안. 남아 있으면 적재 안 됨).
AI(LLM)가 관여하는 코드는 `ENUM_VALUE_SUGGESTED` 하나다 (허용 값의 줄임말은 LLM 없이 규칙으로도 낸다). 나머지 자동 정리는 규칙 기반이다. 행이 없는 이슈(`rowNo` 없음)는 파일·시트 단위다.

| 코드 | 심각도 | 뜻 |
|---|---|---|
| **파일 구조** | | |
| `MISSING_REQUIRED_COLUMN` | 적재 불가 | 필수 필드에 매핑된 열이 없음 |
| `SCHEMA_NOT_READY` | 확인 필요 | 아직 필드 정의가 없는 entity라 형식 검사를 하지 않음 |
| `TOTAL_ROW_SKIPPED` | 확인 필요 | 합계·소계 행을 데이터에서 뺌 |
| `ROW_AFTER_TOTAL` | 확인 필요 | 합계 행 아래에 데이터 행이 있음 |
| **엑셀 수식** | | |
| `FORMULA_ERROR` | 적재 불가 | 수식 결과가 오류값(`#N/A`·`#REF!` 등) |
| `HARDCODED_VALUE` | 확인 필요 | 수식 열인데 값을 직접 입력함 |
| `BLANK_IN_FORMULA_COLUMN` | 확인 필요 | 수식 열인데 빈 칸 |
| `FORMULA_INCONSISTENT` | 확인 필요 | 같은 열의 대표 수식과 모양이 다름 |
| `FORMULA_DIFFERS_ACROSS_SHEETS` | 확인 필요 | 같은 양식 다른 시트와 대표 수식이 다름 |
| **검산** | | |
| `RECALC_MISMATCH` | 확인 필요 | 다시 계산한 값과 저장값이 다름 (계산값은 `suggestion`) |
| `TOTAL_ITEM_MISSING` | 확인 필요 | 지급계·공제계 합에 들어가지 않은 항목 열 |
| **법정 기준** | | |
| `INSURANCE_RATE_OUTDATED` | 확인 필요 | 4대보험 요율이 귀속연도 요율과 다름 |
| `INSURANCE_BASE_INCLUDES_MEAL` | 확인 필요 | 4대보험 기준 금액에 비과세 식대가 들어감 |
| `PREMIUM_BELOW_STATUTORY` | 확인 필요 | 연장·야간·휴일 수당이 법정 배율보다 적음 |
| `BELOW_MINIMUM_WAGE` | 확인 필요 | 월 급여가 최저임금 × 209시간보다 적음 |
| **형식·필수값** | | |
| `REQUIRED` | 적재 불가 | 필수값이 비어 있음 |
| `INVALID_DATE` | 적재 불가 | 날짜로 읽을 수 없음 |
| `DATE_FORMAT` | 자동 정리 | 날짜를 YYYY-MM-DD로 통일 |
| `INVALID_NUMBER` | 적재 불가 | 숫자가 아님 |
| `NUMBER_FORMAT` | 자동 정리 | 숫자의 쉼표 등을 없앰 |
| `INVALID_EMAIL` | 적재 불가 | 이메일 형식이 아님 |
| `INVALID_PHONE` | 확인 필요 | 전화번호 자리 수가 맞지 않음 |
| `PHONE_ZERO_DROPPED` | 자동 정리 | 숫자 칸으로 저장돼 앞자리 0이 빠진 휴대전화 → `010-2271-0888` |
| `INVALID_ENUM` | 적재 불가 | 허용되지 않는 값 (허용 값을 메시지에 적음) |
| `ENUM_VALUE_SUGGESTED` | 자동 정리 | 허용되지 않는 값을 허용 값으로 바꾸자는 제안. LLM 추천(근거는 메시지) 또는 허용 값의 줄임말 |
| `TOO_LONG` | 적재 불가 | 최대 길이를 넘음 |
| **파일 안 정합성** | | |
| `DUPLICATE_KEY` | 적재 불가 | 키(사번·부서코드)가 중복 |
| `DUPLICATE_SUSPECTED` | 확인 필요 | 사번은 다른데 이름·생년월일이 같음 (같은 사람 두 번) |
| `CODE_TYPO_SUSPECTED` | 확인 필요 | 직위·직급 등의 드문 값이 흔한 값과 한 글자만 다름 (제안값 있음) |
| `DATE_ORDER` | 적재 불가 / 확인 필요 | 퇴사일 < 입사일(적재 불가), 인정입사일 > 입사일(확인 필요) |
| `SELF_PARENT` | 적재 불가 | 부서의 상위부서가 자기 자신 |
| `PARENT_NOT_IN_FILE` | 확인 필요 | 상위부서 코드가 조직 파일에 없음 |
| `DEPT_NOT_IN_ORG` | 적재 불가 | 사원의 부서가 조직 파일(부서코드·부서명)에 없음 |
| `ORG_FILE_MISSING` | 확인 필요 | 조직 파일이 없거나 부서코드·부서명이 연결되지 않아 사원 부서를 확인하지 못함 (파일 단위) |
| **직원명부 대조** | | |
| `EMP_NOT_IN_ROSTER` | 확인 필요 · 적재 불가 | 사번이 직원명부에 없음 (적재하는 파일이면 적재 불가) |
| `EMP_NAME_MISMATCH` | 확인 필요 | 사번은 있는데 이름이 다름 |
| `EMP_KEY_MISSING` | 확인 필요 | 사번 빈 칸 (이름으로 찾은 사번 제안) |
| `EMPNO_ZERO_PADDED` | 확인 필요 | 앞자리 0이 빠진 사번을 명부 자리 수로 채워 대조함 |
| `ROSTER_HIRE_DATE_MISMATCH` | 확인 필요 | 입사일이 명부와 다름 |
| `INSURANCE_ACQ_DATE_MISMATCH` | 확인 필요 | 4대보험 취득일 ≠ 입사일 |
| `INSURANCE_LOSS_DATE_MISMATCH` | 확인 필요 | 4대보험 상실일 ≠ 퇴사일 다음 날, 또는 퇴사자인데 상실일 없음 |
| `PAID_OUTSIDE_EMPLOYMENT` | 확인 필요 | 재직 기간 밖 달에 급여가 있음 |
| `GRADE_FROM_POSITION` | 확인 필요 | 직급 열이 없어 직위를 직급으로 씀 (파일 단위) |
| `GRADE_STEP_DEFAULTED` | 확인 필요 | 호봉이 없는 직급이 있음. NEXA에 없으면 1호봉, 하나뿐이면 그 호봉으로 넣음. 여럿이면 기준정보 확인에서 막힘 (파일 단위) |
| `PAY_GRADE_BASE_VARIES` | 확인 필요 | 같은 직급인데 기본급이 그 직급의 최빈값과 다름 (직급별 기본급은 최빈값으로 만들어짐) |
| `FAMILY_COUNT_MISMATCH` | 확인 필요 | 부양가족 수가 급여의 공제가족수와 다름 |
| `SALARY_PAYROLL_MISMATCH` | 확인 필요 | 연봉이력의 월 기본급 ≠ 급여 기본급 |
| `SALARY_HISTORY_MISMATCH` | 확인 필요 | 다른 파일의 연도별 연봉 ≠ 연봉이력 |
| `VISA_EXPIRED_PAID` | 확인 필요 | 체류 기간이 끝난 뒤 달에 급여가 있음 |
| **파일 사이 집계 대조** | | |
| `CROSS_FILE_MISMATCH` | 확인 필요 | 같은 사람·같은 항목의 값이 다른 파일과 다름 (근무표 ↔ 급여 등) |
| `MISSING_IN_COPY` | 확인 필요 | 다른 파일에 있는 사람이 이 파일(신고·지급)에 없음 |
| `SAME_DAY_TWO_SHEETS` | 확인 필요 | 같은 날 두 현장(시트)에 기록됨 |
| **근무표** | | |
| `SHIFT_OVERLAP` | 확인 필요 | 앞날 근무가 끝나기 전에 다음 근무가 시작함 |
| `REST_INTERVAL_SHORT` | 확인 필요 | 근무 사이 휴식이 11시간보다 짧음 (특례 업종) |
| `CONSECUTIVE_WORK_DAYS` | 확인 필요 | 쉬는 날 없이 7일 이상 연속 근무 |
| **업무 규칙** | | |
| `NEGATIVE_NET_PAY` | 확인 필요 | 실지급액이 음수 |
| `PAY_MONTH_GAP` | 확인 필요 | 사람마다 급여가 빠진 달 |
| `DEPENDENT_AGE_RULE` | 확인 필요 | 기본공제 자녀 나이 > 20 또는 부모 나이 < 60 |
| `WITHHOLDING_RATIO_INVALID` | 확인 필요 | 원천징수비율이 80·100·120%가 아님 |
| `RAISE_RATE_AS_PERCENT` | 확인 필요 | 인상률을 비율이 아니라 %로 적음 |
| **조직 부서유형·부서장** | | |
| `DEPT_TYPE_ORDER` | 확인 필요 | 최상위가 회사가 아님, 회사 아래 회사, 상위보다 높은 단계 |
| `HEAD_NOT_IN_ROSTER` | 확인 필요 | 부서장 사번이 직원명부에 없음 |
| `HEAD_NAME_GIVEN` | 자동 정리 | 부서장에 이름을 적음 → 명부에서 한 명뿐인 그 이름의 사번 |
| `HEAD_NOT_EMPLOYED` | 확인 필요 | 부서장이 재직 중이 아님 |
| `HEAD_OUTSIDE_DEPT` | 확인 필요 | 부서장이 그 부서나 하위 부서 소속이 아님 |
| `HEAD_FROM_DUTY` | 자동 정리 | 빈 부서장 → 명부 직책으로 정한 사번 |
| `HEAD_VACANT` | 확인 필요 | 부서장이 비어 있고 직책으로도 정할 수 없음 (공석이면 결재선 자동 결정 409) |
| `HEAD_COLUMN_MISSING` | 확인 필요 | 조직 파일에 부서장 열이 없음 (파일 단위) |

## 대상 서비스 내부 API

user-service(`/internal/v1/hr/migration`)와 payroll-service(`/internal/v1/pay/migration`)가 같은 모양으로 연다. 토큰 없음, gateway 미노출.

```text
POST   {prefix}/{path}/upsert?batchId=   {"rows":[{"rowNo":2,"data":{...}}]} → ApiResult{data:{created, updated}}
DELETE {prefix}/batches/{batchId}
GET    {prefix}/batches/{batchId}/summary                              → ApiResult{data:{count}}

인사 path: references(기준정보) · orgs · employees
          GET  /internal/v1/hr/migration/references                → 지금 있는 직위·직급·직책·직무
          POST /internal/v1/hr/migration/employees/lookup {empNos} → 사번 → 사원 ID·명세서용 사원 정보
급여 path: grades(직급별 기본급) · payslips(지난 급여명세서)
```

## 아직 안 된 것

- `companyId`를 요청 본문으로 받음 (토큰에 회사 정보가 없음. 단일 회사 ERP라 지금은 그대로)
- 임시 테이블(`staging_row`) 민감정보 암호화
- 업로드·적재 비동기 처리 (지금은 동기)
- 대상 서비스 코드 목록과 대조하는 코드 매핑, 엑셀 양식 다운로드, 전환 완료
- 연차·급여 설정 등(`LEAVE_BALANCE`, `PAY_YTD_BALANCE`) 필드 정의: 대상 서비스와 합의 전이라 없음 (지금은 읽기·수식 검사·직원명부 대조만)
- 수식 규칙의 설정 시트 참조(`설정!$B$9`)를 값·라벨로 풀어 보여 주기 (지금 규칙 요약은 '설정 시트 B9 값'으로만 적음), ROUND·VLOOKUP이 섞인 수식 검산, 국민연금 상·하한, 파일 사이 대조(4대보험 보수월액 ↔ 급여)
