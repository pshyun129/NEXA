# 프론트엔드 요구사항 정리

백엔드 7개 서비스(user, approval, attendance, leave, payroll, migration, ai(FastAPI))와 gateway 코드를 읽고, 프론트가 만들어야 할 화면과 지켜야 할 규칙을 정리한 문서다.

- 기준: `develop` 계열 브랜치 코드 (2026-11-04). 설계 문서는 `DOCS/*.md`.
- 코드와 이 문서가 다르면 **코드가 맞다**. Notion API 명세서는 갱신되지 않은 부분이 있다.
- gateway 라우트는 외부 config 저장소에 있어 확인하지 못했다. 아래 경로는 각 서비스 컨트롤러 경로이며, gateway가 prefix를 그대로 넘긴다고 가정한다.

---

## 1. 공통 기반

### 1.1 인증

| 항목 | 내용 |
|---|---|
| 방식 | HttpOnly 쿠키. `accessToken`(Path `/`, 15분), `refreshToken`(Path `/api/v1/auth`, 7일) |
| 요청 | 모든 요청에 `credentials: 'include'` (axios `withCredentials: true`). `Authorization` 헤더는 붙이지 않는다 (gateway가 덮어쓴다) |
| 로그인 | `POST /api/v1/auth/login` body `{ email, pass }` ← 필드명이 `password`가 아니라 **`pass`** |
| 로그인 응답 | `{ employeeId, deptName, gradeName }`. **role·name이 없다** |
| 재발급 | `POST /api/v1/auth/reissue` (본문 없음). access·refresh 둘 다 새로 발급 |
| 로그아웃 | `POST /api/v1/auth/logout`. 항상 200, 쿠키 삭제 |
| 세션 복원 | `/me` API가 **없다**. 로그인 응답의 `employeeId`를 저장(localStorage 등)해 두고, `GET /api/v1/employees/{employeeId}`로 `name`, `role`, `deptId` 등을 받는다 |

**필수 구현**

1. **401 처리**: gateway의 401은 사유를 구분하지 않는다(`code: "UNAUTHORIZED"`). 401을 받으면 `/reissue`를 한 번 시도하고, 성공하면 원 요청을 재시도, 실패하면 로그인 화면으로 보낸다.
   - **제외 대상**: `/api/v1/auth/*` 요청(로그인 실패 `AUTH_005`, reissue 실패 `AUTH_007/008`도 401이다)과 이미 한 번 재시도한 요청(`_retry` 플래그)은 reissue하지 않는다. 그렇지 않으면 루프가 생기거나 로그인 오류 메시지가 사라진다.
   - reissue가 실패하면 대기 중인 요청을 모두 reject하고 로그인 화면으로 **한 번만** 이동한다.
2. **reissue 단일화**: refresh 토큰은 회전(rotation)되고, 이미 쓴 refresh를 다시 쓰면 세션이 통째로 삭제된다. 동시에 여러 요청이 401을 받아도 **reissue는 한 번만** 호출하고 나머지는 그 결과를 기다리게 한다 (공유 Promise).
   - 공유 Promise는 탭 하나 안에서만 효과가 있다. 여러 탭이 동시에 reissue하면 세션이 삭제될 수 있으므로 **한 사원은 한 탭만 쓰는 것을 전제**로 한다(시연도 계정당 창 1개). 숨겨진 탭은 폴링을 멈춘다(설계 7.3). 다중 탭 직렬화(`navigator.locks`)는 P2.
3. **한 사원 = 한 세션**: 다른 기기에서 로그인하면 이전 기기는 reissue 시점에 로그아웃된다.
4. **로그인 오류 표시**
   - `AUTH_005` 이메일/비밀번호 불일치, `AUTH_006` 퇴직 계정, `AUTH_010`(423) 잠김
   - 5회 실패(10분 내) 시 15분 잠김. 남은 횟수는 응답에 없다.
5. 비밀번호 변경·재설정, 첫 로그인 비밀번호 변경 기능은 백엔드에 **없다**.
6. 토큰의 소속 정보(부서·직급·직책)는 발급 시점 값이라 인사 변경이 최대 15분 늦게 반영된다.

### 1.2 CORS / 개발 환경

- gateway 허용 origin: `http://localhost:5173` 하나 (Vite 기본 포트). 운영 origin은 아직 없다.
- 허용 헤더: `Authorization`, `Content-Type`, `X-Request-Id`만. **다른 커스텀 헤더를 보내면 preflight 실패**.
  - leave-service는 `Idempotency-Key` 헤더가 필수인데 gateway CORS에 없다. **아래 표준 구성(같은 origin 프록시)을 쓰면 preflight가 생기지 않아 문제가 되지 않는다** → 6장 #1은 🟡.
- **표준 구성(확정)**: 브라우저는 항상 프론트와 **같은 origin**으로만 요청한다.
  - 개발: `vite.config`의 `server.proxy`로 `/api`를 gateway로 넘긴다(`{ '/api': { target: <gateway 주소, env VITE_GATEWAY_URL>, changeOrigin: true } }`). 프록시에서 `Origin` 헤더를 제거하면(`configure: p => p.on('proxyReq', r => r.removeHeader('origin'))`) gateway가 CORS 요청으로 보지 않으므로 `vite --host` LAN 접속에서도 막히지 않는다. 1주차에 실측한다.
  - 운영(시연 서버): nginx가 정적 파일과 `/api` 리버스 프록시를 같은 origin으로 서빙한다. 같은 origin이므로 `SameSite=Lax` 쿠키 문제도 없다(7장 #2 종결).
  - axios `baseURL`은 비워 두고(같은 origin) 이 문서의 `/api/v1/...` 전체 경로를 그대로 쓴다. `withCredentials: true`는 유지한다.
- 응답 헤더 `X-Request-Id`를 읽을 수 있다. 오류 화면/로그에 함께 표시하면 백엔드 추적이 쉽다.
- 개발 계정: `admin@local.dev` / `admin1234!` (ADMIN), `user1~9@local.dev` (사원 101~109, 같은 비밀번호).

### 1.3 응답 형식 (두 가지를 모두 처리)

```jsonc
// 서비스 응답 (user, approval, attendance, leave, payroll, migration, ai 공통)
{ "success": true,  "error": null, "data": { ... } }
{ "success": false, "error": { "code": "APPROVAL_024", "message": "..." }, "data": null }

// gateway가 직접 막을 때 (401/403/502/504) — 봉투가 아님
{ "code": "UNAUTHORIZED", "message": "...", "requestId": "<uuid>" }
```

- 오류 메시지는 `error.message`를 그대로 보여 주면 된다. 서비스가 상세 메시지를 채워 주는 경우가 많다.
- `COMMON_001`(400) 검증 실패 메시지는 `"field: msg, field2: msg"` 형태다. 필드별로 쪼개 폼에 붙일 수 있다.
- FastAPI(ai)도 같은 봉투를 쓰고, 검증 실패도 422가 아니라 400 `COMMON_001`이다.

### 1.4 데이터 공통 규칙

| 항목 | 규칙 |
|---|---|
| 페이지네이션 | **전 서비스에 없다** (migration 오류 목록만 page/size). 목록은 전체를 받아 클라이언트 페이징·필터·정렬 |
| 목록 응답 모양 | 배열이 바로 오지 않고 **래핑 객체**로 오는 경우가 많다. 예: `GET /employees` → `data.employees[]`, `GET /documents` → `data.documents[]` + `totalCount`. 서비스별로 응답 타입을 정의한다 |
| 사원 이름 | 거의 모든 응답이 `empId`만 준다. `GET /api/v1/employees?ids=1,2,3`(최대 100개)으로 모아서 조회하고 캐시한다. `ids`와 `deptId`를 함께 보내면 400, 없는 ID는 조용히 빠진다(퇴직자는 포함) |
| 부서·직급·직위 이름 | 사원 응답에도 ID만 있다. 우회 경로: **부서명**은 일반 사원이면 `GET /employees/{id}/org-chain`의 `chain[].deptName`, 관리자면 `/admin/departments`. **직책명**은 `GET /api/v1/duties/{dutyId}`(단건, 인증 사용자 모두). **본인 부서·직급명**은 로그인 응답의 `deptName`, `gradeName`(세션과 함께 저장). 나머지(직위·직무 목록)는 6장 #5 |
| 날짜 | `LocalDate` = `YYYY-MM-DD`, `YearMonth` = `YYYY-MM`, 시각 = ISO-8601 오프셋 문자열 |
| 시간대 | 표시·날짜 계산은 모두 `Asia/Seoul`. leave의 `leaveDate`는 UTC(`...T15:00:00Z`)로 올 수 있으니 **서울 시간으로 변환 후 날짜를 뽑는다**. 라이브러리는 **dayjs + `utc`/`timezone` 플러그인**(`dayjs.tz.setDefault('Asia/Seoul')`, AntD DatePicker와 같은 라이브러리)이고, 날짜 유틸은 `src/format/` 한 곳에 둔다. date-fns·luxon은 쓰지 않는다 |
| 수량 단위 | 근태·휴가 시간은 **분(int)**. 하루 = 480분, 반차 = 240분. 금액은 원(int) |
| 비동기 결과 | 결재 결과는 Kafka로 각 서비스에 늦게 반영된다. 푸시/SSE 없음 → 재조회. React Query 기본값: `refetchOnWindowFocus: true`, 진행 중 상태(PENDING·SUBMITTED·SUBMITTING)인 행·상세만 `refetchInterval` 5초, 2분이 지나도 그대로면 멈추고 "반영 지연" 표시. 실행 모니터는 설계 7.3 값을 따른다 |
| 멱등 키 | 휴가 신청·휴가 잔액 조정은 `Idempotency-Key` 필수, 결재 직접 기안은 `requestKey`(UUID) 권장. **화면을 열 때 UUID를 만들고(폼 상태에 보관) 재시도 시 같은 값 유지, 성공 응답(200/201)을 받은 뒤 새 신청을 시작할 때만 새로 만든다**. `crypto.randomUUID()`는 https나 localhost에서만 동작하므로 **`uuid` 패키지(v4)** 를 쓴다(http IP로 띄운 시연 서버 대비) |

### 1.5 권한 모델

- 토큰 `role`: `USER` / `ADMIN` 두 가지뿐. 관리자 메뉴는 `role === 'ADMIN'`으로 노출한다.
- 관리자 API 경로: `/api/v1/admin/**`(user, migration), `/api/v1/leaves/admin/**`, `/api/v1/attn/admin/**`, `/api/v1/pay/admin/**`.
- 예외: 부서 휴가 API는 role이 아니라 직책명 `"휴가 관리자"` + 본인 부서로 판정한다 (현재 항상 503 `LEAVE_017`: `LEAVE_HR_ENABLED=false`이고 HR URI·JWT claim 설정이 비어 있음, 6장 #11). P2.
- attendance TODO에 "팀장급 직책 기반 권한으로 전환 예정"이 있다. 권한 체크 로직을 한곳에 모아 두는 것이 좋다.
- 권한 없는 결재 문서·근태 신청·명세서는 403이 아니라 **404**로 온다 (존재 여부를 숨김).

---

## 2. 메뉴 구성 (제안)

```
[사원]
 ├ 홈(대시보드)        출퇴근 위젯, 이번 주 근로시간, 결재 대기, 자동화 확인 대기 (P0) / 휴가 잔여 (P1)
 ├ 근태               오늘·주간·근태 신청(정정·초과근무·휴일대체)·신청 목록·회수 (P0) / 월간·상태 이력 (P1)
 ├ 휴가               잔여·휴가 신청·내 신청 내역 (P1, 블로커 해소 시) / 발생·원장·내 정책 (P2 → 2026-11-09 함께 구현)
 ├ 전자결재            결재 대기·기안함·전체 / 문서 상세(승인·반려·전결·회수) (P0) / 기안(일반·출장)·재기안·참조자 추가 (P1)
 ├ 급여               명세서 목록·상세 / 내 직급 급여 정보 / 퇴직금 예상 (P1)
 ├ 업무 자동화         실행 가능한 자동화·실행 모니터·확인 대기함 (P0) / 내 실행 이력 (P1)
 └ 조직도             내 결재 라인(org-chain 세로 스테퍼) + 사원 검색 (P1). 트리 조직도는 관리자 화면에만

[관리자]
 ├ 인사               부서 트리·부서장·유형 (P1, 시드에 필요) / 사원 목록·퇴직 처리 (P1) / 사원 등록 (P2)
 ├ 근태 관리           (P2) 사원별 일 근태 조회, 로그 등록 — 수정·삭제는 #9로 불가
 ├ 휴가 관리           (P2) 정책·버전 조회와 사원별 잔액 조회만. 편집·배정·조정·취소는 시드 SQL/API로 대체
 ├ 급여 관리           (P2) 명세서 생성
 ├ 데이터 이관         HR_ORG·HR_EMPLOYEE 업로드·매핑·검증·자동 정리·미리보기·적재·대사 (P0) / 행 수정·되돌리기 (P1) / 수식·LLM 요약 (P2)
 └ 자동화 빌더         시드 워크플로 열기·노드 추가·연결·설정·검사·저장·테스트 실행 (P0) / 배포·버전·사원 노출(HOME 토글) (P1) / 입력 폼·노출 화면 탭·복제·롤백 UI (P2, 시드 스크립트로 대체)
```

**우선순위 기준 (설계 9장과 같은 기준)**
- **P0**: 시연 대본(설계 8.3)에 나오는 동선. 6주 안에 반드시 완성한다.
- **P1**: ERP로서 있어야 하지만 시연에 나오지 않는 화면. 5주차까지 여유가 있을 때만 만든다.
- **P2**: 만들지 않는다. 필요한 데이터는 시드 스크립트(API 호출)나 SQL로 넣는다.

---

## 3. 화면별 요구사항 — 사원

### 3.1 홈(대시보드)

| 위젯 | API |
|---|---|
| 출퇴근 버튼 + 현재 상태 | `GET /api/v1/attn/today`, `POST /api/v1/attn/clock-in`, `/clock-out`, `/status` |
| 이번 주 근로시간 (소정/최대/잔여) | `GET /api/v1/attn/me/weekly?date=오늘` → `summary.totalMin / stdMin / maxMin / remainStdMin` |
| 휴가 잔여 (P1) | `GET /api/v1/leaves/me/summary`. grant가 없으면 잔액 0이므로(6장 #4) 시드가 없으면 "발생 내역 연동 대기" 빈 상태 |
| 결재할 문서 | 전용 API 없음. 3.4의 결재 대기 계산(`useApprovalBox`) 결과를 그대로 쓴다 |
| 자동화 확인 대기 | `GET /api/v1/ai/runs/me/pending` |

### 3.2 근태

**출퇴근 위젯 규칙** (`AttendanceStatusResponse`)
- 출근 전: `statusCode=null`, `ableStatusList=[]` → "출근" 버튼만.
- 출근은 **하루 1회** (`ATTN_002`). 지각(`LATE`)은 서버가 판정.
- 근무 중 상태 전환 버튼은 `ableStatusList`로 그린다 (`WORKING` 업무 / `OFF_WORKING` 업무종료 / `LEAVE` 휴가). 외근·출장·휴게 상태는 없다.
- 퇴근 후: `ableStatusList=[WORKING]`이면 "퇴근 취소" 버튼(= `status`에 `WORKING` 전송).
- 퇴근 후 2시간이 지나면 변경 불가(`ableStatusList=[]`). 이후 수정은 근태 신청(TIME_FIX)으로 안내.
- 자정을 넘긴 퇴근은 출근한 날로 기록된다.

**조회 화면**

| 화면 | API | 비고 |
|---|---|---|
| 일간 | `GET /api/v1/attn/me/daily/{date}` | `records`(타임라인), `workTime`, `anomalies`. 기록 없는 날도 200 |
| 주간 | `GET /api/v1/attn/me/weekly?date=` | 월~일 7칸 고정 |
| 월간 | `GET /api/v1/attn/me/monthly?month=YYYY-MM` | 1일~말일. 캘린더 + 근태이상 배지 |
| 상태 이력 | `GET /api/v1/attn/me/history?from=&to=` | 최대 31일. 삭제된 로그 포함(`isDeleted`), `source`(SELF/ADMIN/APPROVAL) 표시 |
| 공휴일 | `GET /api/v1/attn/holidays?startDate=&endDate=` | 캘린더 휴일 표시 |
| 상태 코드 | `GET /api/v1/attn/status-codes` | 라벨 사전 |

근로시간(`WorkTimeResponse`, 분): `totalMin` 총, `regularMin` 소정, `nightMin` 소정 중 야간, `otExtendedMin` 연장, `otNightMin` 초과 야간, `otHolidayMin` 휴일, `breakMin` 휴게, `approvedOtMin` / `unapprovedOtMin` 승인/미승인 초과. 퇴근 전에는 모두 0. 화면에서는 `h시간 m분`으로 변환.

### 3.3 근태 신청 (근무시간 수정 · 초과근무 · 휴일대체)

흐름: 유형 선택 → 입력 → `POST /api/v1/attn/me/requests/preview` → 결과 확인 → `POST /api/v1/attn/me/requests`(사유 필수, 500자) → 상세.

| 유형 | 입력 | 규칙 / preview 표시 |
|---|---|---|
| `TIME_FIX` 근무시간 수정 | `targetDate`(과거·오늘), `items[]{action: ADD/MODIFY/DELETE, targetLogId, statusCode, startAt}` | MODIFY/DELETE의 `targetLogId`는 `/me/history`의 `logId`에서 고른다 (일간 응답엔 logId 없음). 출근·퇴근 로그는 각 1건 이하. preview: 수정 전/후 근로시간, 주간 합계 |
| `OVERTIME` 초과근무 | `targetDate`, `items[]{statusCode: WORKING/CLOCK_IN, startAt, endAt}` | 미래 날짜 가능. 주 52시간 초과 시 409 `ATTN_011`. preview: 신청분, 주간 합계, 남은 시간 |
| `HOLIDAY_SUBST` 휴일대체 | `targetDate`(휴일), `substDate`(근무일) | 같은 날 중복 불가 |

- `startAt`은 근무일 당일 또는 다음 날만 허용, 오프셋 포함 ISO 문자열(`2026-11-04T09:00:00+09:00`)로 보낸다.
- 결재선은 입력받지 않는다 (approval이 자동 생성). 상신하면 응답에 `approvalDocId`, `docNo`가 온다 → 결재 문서 상세 링크.
- 목록: `GET /api/v1/attn/me/requests?year=&requestType=` (상태 `PENDING/APPROVED/REJECTED/CANCELED`). **`year`는 필수**(없으면 400). 기본값은 올해.
  - 목록 응답(`AttendanceRequestSummaryResponse`)에는 **`approvalDocId`가 없다**. 결재 문서로 가려면 상세(`/me/requests/{id}`)를 1회 조회한다(값이 바뀌지 않으므로 캐시 무기한). 목록에는 `completedAt`(결재 완료 반영 시각)이 있다.
- 회수: `POST /api/v1/attn/me/requests/{id}/cancel`. `PENDING`이고 아무도 결재하지 않았을 때만 (`ATTN_016`, `ATTN_017`). **동기 처리**다(attendance가 결재 문서를 직접 회수하고 그 자리에서 `CANCELED`로 응답). 응답값으로 즉시 갱신하고 비동기 대기 표시는 하지 않는다.
- 상신 직후 상태는 항상 `PENDING`. 승인·반려 결과는 Kafka로 비동기 반영.

### 3.4 전자결재

**문서함**

| 탭 | 구현 방법 |
|---|---|
| 결재 대기 (P0) | **API 없음**(6장 #7, 시연 블로커). 아래 임시 계산 규칙으로 구현한다 |
| 기안함 (P0) | `GET /api/v1/documents?box=DRAFTED` |
| 전체(내가 볼 수 있는 문서) (P0) | `GET /api/v1/documents?approvalStatus=&formType=` |
| 참조함 / 완료함 | P2. 백엔드 `box` 추가 시 복원 |

- 목록 응답은 `{ documents: [...], totalCount }`이고, 항목은 `documentId, docNo, formType, title, status, drafterId, draftedAt, completedAt`뿐이다. **`sourceType`, 결재선, 현재 결재자, 내 차례 여부가 없다**. 정렬은 `draftedAt DESC` 고정.
- `docNo` 형식은 `{FormType}-{연도}-{4자리}`(예: `GENERAL-2026-0001`, `HOLIDAY_SUBST-2026-0003`, 최대 23자).
- **결재 대기 임시 계산(N+1)**: ① `GET /documents?approvalStatus=INPROGRESS`로 범위를 줄인다. ② `drafterId ≠ 나`인 문서만, 최근 50건 상한으로 상세를 **동시 4개**까지 조회한다(React Query 키 `['document', id]`, 상세 화면과 캐시 공유). ③ APPROVER/AGREEMENT·PENDING 중 최소 step이 나인 문서를 대기로 둔다. ④ 목록은 30초 + 포커스로 재조회하고 새로 나타난 ID만 즉시 상세를 가져온다. 상세 staleTime 30초, 승인·반려 후 해당 문서를 invalidate한다. ADMIN은 목록이 "조회 가능한 전체"라 호출이 많으므로 관리 모드에서는 카운터를 끈다. `box`가 생기면 훅 내부만 바꾼다.
- 양식: `GENERAL` 일반기안, `BUSINESS_TRIP` 출장 — **화면에서 직접 기안 가능한 것은 이 둘뿐**. `LEAVE`, `OVERTIME`, `TIME_FIX`, `HOLIDAY_SUBST`는 휴가·근태 화면에서 생기고, `GENERAL`+`AUTOMATION`은 자동화가 만든다.

**기안 화면**
1. 양식 선택, 제목(255자), 속성 입력: 출장 → `overseas`(해외 여부), 일반 → `amount`(금액).
2. 값이 바뀔 때마다 `GET /api/v1/documents/approval-line-preview?formType=&overseas=&amount=` → 자동 결재선 표시. `selfApproval=true`면 "결재 없이 바로 승인됩니다" 안내.
3. `POST /api/v1/documents` `{ title, formType, requestKey, attributes }`. 결재선은 보내지 않는다.
4. 상신 직후 필요하면 결재자 추가·참조자·열람자 API 호출.
- **본문·첨부파일·임시저장 필드가 없다.** 문서에 저장되는 것은 제목과 속성뿐이다 (6장).
- 전결 단계(현재 설정 예시, 값은 `ApprovalPolicyProperties`가 외부 config에서 읽으므로 미검증): 출장 해외 → 본부장, 일반 금액 100만 원 초과 → 본부장 / 1,000만 원 초과 → 대표이사, 초과근무 그 달 누적 12시간 초과 → 본부장, 휴가 5일 이상 → 본부장. **프론트는 임계값을 하드코딩하지 않고** `approval-line-preview` 응답의 결재선만 표시한다.

**문서 상세** (`GET /api/v1/documents/{id}`)
- 결재선 표시: `approvalLines`(APPROVER 결재 / AGREEMENT 합의 / REFERENCE 참조 / VIEWER 열람), `action`(PENDING/AGREE/DISAGREE/SKIPPED), `required`(규정상 필수 결재자).
- **현재 차례 계산**: role이 APPROVER/AGREEMENT이고 action이 PENDING인 줄 중 step이 가장 작은 사람.
- 이력: `histories`(round=재기안 차수, action, comment). 재기안 차수별로 묶어 표시.
- 원본 연결: `sourceType`(LEAVE/ATTENDANCE/AUTOMATION)과 `sourceId`로 원 신청 화면 링크.

**버튼 노출 조건**

| 버튼 | 조건 | API |
|---|---|---|
| 승인 | 내 차례 | `POST /{id}/approve` |
| 반려 | 내 차례, 사유 필수(1000자) | `POST /{id}/reject` `{comment}` |
| 전결 | 내 차례 && role=APPROVER && 뒤에 required 결재자 없음 | `POST /{id}/final-approve` |
| 회수 | 기안자 && INPROGRESS && 아직 아무도 결재 안 함 | `POST /{id}/cancel` |
| 재기안 | 기안자 && REJECTED && `sourceType=DIRECT` | `POST /{id}/resubmit` `{title}` |
| 결재선 편집 (P1) | 기안자 && INPROGRESS | P1은 **참조자 추가(`/referrers`) 하나**만. 열람자·결재자 추가·삭제는 P2, 순서 변경(`PATCH /approvers/order`, 결재자 전체 목록 전송)도 P2 |

결재선 편집 규칙(클라이언트는 2개만): ① 기안자이고 INPROGRESS일 때만 편집 UI를 노출한다. ② `required:true` 결재자 행은 잠근다(삭제 버튼 없음). 그 밖의 규칙(이미 결재한 사람 앞 추가 불가, 기안자 본인·중복 추가 불가, 마지막 결재자 삭제 불가 등)은 서버가 `APPROVAL_002/003/008/010/011/024`로 검증하므로 **오류 메시지를 토스트로 그대로 보여 준다**.

출처 문서(휴가·근태·자동화)는 반려가 최종이다. 재기안 대신 "원래 화면에서 새로 신청" 안내.

### 3.5 휴가

**조회**

| 화면 | API |
|---|---|
| 잔여 현황 | `GET /api/v1/leaves/me/summary` (`quantityMinutes` 보유, `reservedMinutes` 결재 중, `remainingMinutes` 사용 가능) |
| 발생 내역 | `GET /api/v1/leaves/me/grants` (`grantKind`, 유효기간) |
| 사용 원장 | `GET /api/v1/leaves/me/ledger` (`eventType`: RESERVE/CONSUME/RELEASE/ADJUST …) |
| 내 정책 | `GET /api/v1/leaves/me/policy` (없으면 404 `LEAVE_010` → "정책이 배정되지 않았습니다") |

**신청 화면**
1. `GET /api/v1/leaves/types` → 신청 가능 유형, `allowedUnits`(FULL/HALF/MIXED/HOUR), 최소·최대 분.
2. 날짜(복수)와 날짜별 `portion`(FULL 종일 / AM_HALF 오전 반차 / PM_HALF 오후 반차) 선택. 시간 단위 신청 불가.
3. 선택이 바뀔 때마다 `POST /api/v1/leaves/me/requests/preview` → `reasons[]`를 날짜별 오류로 표시, 날짜별 차감·배분 표시.
   - `validationStatus`: REQUESTABLE / NOT_REQUESTABLE / UNVERIFIABLE(공휴일 검증 실패 등 → "잠시 후 다시")
   - 요청 본문에 정의되지 않은 필드를 넣으면 400.
4. `requestable=true`일 때만 제출 활성화 → `POST /api/v1/leaves/me/requests` + 헤더 `Idempotency-Key`(화면 진입 시 UUID 생성).
   - 201 `SUBMITTED` 완료 / **202 `SUBMITTING`은 "확인 필요" → 같은 키로 재시도 버튼** / 200은 기존 결과 재반환.

클라이언트에서 미리 막을 규칙은 **과거 날짜 비활성화(서울 기준 오늘은 가능), 같은 날짜 중복 선택 방지, 최대 366일, 사유 2000자**만 둔다. 주말·공휴일·이미 신청된 날짜·반차 조합은 preview `reasons[]`를 날짜 셀에 표시하는 것으로 대신한다(서버 규칙과 어긋나지 않게, 목록에 날짜가 없어 기신청일을 미리 알 수도 없다).

**내 신청 내역**
- 목록 `GET /api/v1/leaves/me/requests` — **날짜가 없다**. 기간 표시는 상세(`/me/requests/{id}`)의 `dates[]`가 필요.
- 상태: `DRAFT` 작성 중, `SUBMITTING` 처리 확인 중, `SUBMITTED` 결재 중, `APPROVED`, `REJECTED`, `WITHDRAWN`(본인 회수), `CANCELLED`(관리자 취소 **또는 결재 문서 생성 실패 확인** → 상세에서 응답 `message`를 보여 준다).
- 결재 진행 상황은 `approvalDocId`로 결재 문서 상세를 연다.
- **본인 취소**: leave에 API가 없다. 결재 문서 회수(`POST /api/v1/documents/{approvalDocId}/cancel`)로 하고, 결과는 Kafka를 거쳐 `WITHDRAWN`이 된다. **leave의 결재 결과 수신(`LEAVE_APPROVAL_RESULTS_ENABLED` 등, 6장 #3)이 켜져 있어야** 반영된다. 꺼져 있으면 `SUBMITTED`에 머문다.
- 결재 직전 결재선 미리보기: `GET /api/v1/documents/approval-line-preview?formType=LEAVE&days=` (5일 이상이면 본부장).

### 3.6 급여

| 화면 | API | 비고 |
|---|---|---|
| 명세서 목록 | `GET /api/v1/pay/me/payslips?year=` | 월별 + 연간 합계(`total`) |
| 명세서 상세 | `GET /api/v1/pay/me/payslips/{id}` | 사원 정보 스냅샷, 근로시간, `earnings[]`, `deductions[]`(0원 항목 포함) |
| 내 직급 급여 정보 | `GET /api/v1/pay/me/contract` | 개인 연봉 계약이 아니라 **토큰 `gradeId` 기준 직급 기본급**이다. `PAY_002`는 `error.message`를 그대로 표시한다(토큰에 직급이 없으면 서버가 "다시 로그인" 문구를 주고, 직급 기본급 미등록이면 "직급별 기본급이 등록되지 않았습니다"). 시드에 `pay_grade` 기본급을 넣는다(8장) |
| 퇴직금 예상 | `GET /api/v1/pay/me/retirement-estimate?resignDate=` | 1년 미만이면 0, 명세서 없으면 `PAY_007` |

- PDF·엑셀 다운로드 API 없음 → 인쇄용 레이아웃(`@media print`) 또는 클라이언트 PDF 생성.
- 명세서 상태(확정·발송) 개념 없음. 생성 즉시 조회 가능.

### 3.7 업무 자동화 (사원 실행)

1. ERP 각 화면에 "자동화 실행" 진입점: `GET /api/v1/ai/workflows/available?screen=<화면코드>`. 서버는 자유 문자열을 받으므로 프론트가 **화면 코드 상수**를 정의하고 빌더 `placements`와 각 화면이 같은 값을 쓴다: `SCREEN_CODES = ['HOME', 'ATTENDANCE', 'LEAVE', 'APPROVAL_DRAFT', 'PAYROLL']`. 시연은 `HOME` 하나만 쓴다.
2. 입력 폼: `GET /api/v1/ai/workflows/{id}/input-form` → `fields[]`(text/number/date/select/file)로 동적 렌더링. `prefill`은 미구현.
3. 파일은 먼저 `POST /api/v1/ai/runs/files`(multipart `file`, 20MB, OCR은 jpg/png/bmp/webp만) → `id`. 업로드 취소는 `DELETE /runs/files/{id}`(메타만, 원본 다운로드 API 없음).
4. 실행 `POST /api/v1/ai/workflows/{id}/runs` `{inputs, fileIds}` → 202 `{runId, status: QUEUED}`.
5. **실행 모니터**: `GET /runs/{runId}`와 `/runs/{runId}/steps`를 **함께** 폴링한다. QUEUED·RUNNING은 1.5초, WAITING은 10초, `SUCCEEDED/FAILED/CANCELED`이면 중단, resume 후 다시 1.5초(설계 7.3). 상태 `QUEUED → RUNNING → WAITING → SUCCEEDED / FAILED / CANCELED`.
   - 실행 취소 `POST /runs/{runId}/cancel` (QUEUED·WAITING에서 노출). CANCELED는 이것으로만 생긴다.
   - **사람 확인에서 반려해도 run은 `SUCCEEDED`로 끝난다**(`human_confirm`이 '반려' 분기로 나가고, 연결된 엣지가 없으면 END). 최종 표시는 `run.status` + 마지막 step의 `branch`로 계산한다(예: SUCCEEDED + '반려' → "반려로 종료").
   - `RunDetail`에는 **실행된 그래프가 없다**(6장 #18). 캔버스는 `GET /workflows/{id}`의 초안 그래프로 그리므로, 시연 워크플로는 배포 후 초안을 수정하지 않는다.
6. **사람 확인**(`WAITING`): step의 `output.waiting_for`에서 `fields`(AI 추출값), `reasons`, `gms_fields`(교차 추출값)를 보여 주고 수정 폼 제공 → `POST /runs/{id}/resume` `{approved, comment, data}`. **요청자 본인만** 재개 가능.
   - 영수증 `fields`는 **`store`(가맹점), `total`(금액), `date`(날짜) 3개**뿐이다.
   - `reasons`는 자유 문자열이다: `"OCR 신뢰도 낮음: 0.71"`, `"GMS와 불일치: total"`, `"규칙 위반: store"`. 그대로 목록으로 보여 준다.
   - `llm_verify`는 앞 단계 사유(규칙 위반, OCR 신뢰도 미달)가 **하나도 없을 때만** GMS 교차 추출을 한다. 그래서 "신뢰도 미달"과 "GMS 불일치"는 한 실행에 함께 나오지 않고, 신뢰도 미달이면 `gms_fields`는 null이다. 기준값은 노드 config `min_confidence`(llm_verify 기본 0.9, ocr_extract 기본 0.8)이며 응답에 실리지 않는다.
7. 내 실행 이력 `GET /runs/me?status=`, 확인 대기함 `GET /runs/me/pending`.

- JSON 바깥 필드는 camelCase, `config`·`inputs`·`outputs` 안쪽은 snake_case.
- 업로드한 원본 이미지를 다시 볼 다운로드 API가 없다 (6장).

---

## 4. 화면별 요구사항 — 관리자

### 4.1 인사 (user-service)

| 화면 | API | 비고 |
|---|---|---|
| 사원 목록 | `GET /api/v1/employees?deptId=` | 전체 배열, 퇴직자 포함, 정렬 없음 → 클라이언트 검색·필터. `deptId`는 하위 부서 미포함 |
| 사원 상세 | `GET /api/v1/employees/{id}` | 개인정보(연락처·주소 등)는 응답에 없음 |
| 사원 등록 | `POST /api/v1/employees` | 아래 필드. 이메일 중복 `EMP_002` |
| 퇴직 처리 | `PATCH /api/v1/employees/{id}/terminate` `{resignDate, resignReason}` | 처리 후에도 access 토큰은 최대 15분 유효 |
| 조직도 / 결재 라인 | `GET /api/v1/employees/{id}/org-chain` | 소속 → 최상위 순서, `chain[]`에 `deptId, deptName, deptType, headEmpId`(재직 부서장만). 일반 사원도 호출 가능하므로 사원 메뉴 "조직도"는 이것과 사원 검색으로 만든다 |
| 부서 관리 | `GET /api/v1/admin/departments` | `parentDeptId`로 트리 조립 |
| 부서장 지정·해제 | `PUT` / `DELETE /api/v1/admin/departments/{deptId}/head` | 재직자만(`DEPT_002`), 그 부서 또는 하위 부서 소속만(`DEPT_003`) |
| 부서 유형 변경 | `PUT /api/v1/admin/departments/{deptId}/type` `{deptType}` | COMPANY는 최상위만, 상위보다 높은 단계 불가(`DEPT_004`) |

사원 등록 필드: `email`, `password`(8~72자), `name`, `engName`, `empNo`, `deptId`, `positionId`, `gradeId`, `dutyId`, `jobFamilyId` → `jobKindId` → `jobRoleId`(계층), `empType`, `hireType`, `hireDate`, `recogHireDate`, `officePhone`, `mobile`, `nationality`, `gender`, `birthDate`, `address`, `leavePolicyId`, `workGroupId`, `role`.
⚠ 직위·직급·직책·직무코드 **목록 API가 없어** select 옵션을 채울 수 없다 (6장 #5). 사원 등록 화면은 P2이고, 만든다면 ID 직접 입력으로 단순화한다.

부서장 변경은 이후 상신하는 결재 문서부터 적용된다. 부서에 유형·부서장이 비어 있으면 모든 상신이 `APPROVAL_022`로 막히므로, 부서 관리 화면에서 "결재선 구성 불가" 경고를 띄우면 좋다.

### 4.2 근태 관리 (attendance) — P2

#9(logId 없음) 때문에 수정·삭제 화면을 만들 수 없다. 만든다면 일 근태 조회와 로그 등록만 둔다. 시연 시드(전날 출퇴근 로그)는 아래 등록 API를 스크립트로 호출한다(8장).

| 화면 | API |
|---|---|
| 사원별 일 근태 | `GET /api/v1/attn/admin/employees/{empId}/daily/{date}` |
| 로그 등록 | `POST /api/v1/attn/admin/logs` `{empId, workDate, statusCode, startAt, reason}` |
| 로그 수정 | `PUT /api/v1/attn/admin/logs/{logId}` `{statusCode, startAt, reason}` — 수정하면 **logId가 바뀐다** |
| 로그 삭제 | `DELETE /api/v1/attn/admin/logs/{logId}` (소프트 삭제) |

- 세 변경 API 모두 재계산된 일 근태를 돌려주므로 그대로 화면 갱신.
- ⚠ 일 근태 응답 `records`에 `logId`가 없어 **관리자가 수정·삭제 대상을 고를 수 없다** (6장).
- 일자별·기간별 전 사원 현황, 엑셀 다운로드는 미구현.

### 4.3 휴가 관리 (leave) — P2

화면은 만들지 않는다. 만든다면 **정책·버전 조회와 사원별 잔액 조회만** 둔다. 유형·정책·규칙·배정·조정은 시드 스크립트(아래 API)나 SQL로 넣는다. 아래 표는 API 참고용이다.

| 화면 | API |
|---|---|
| 휴가 유형 | `GET/POST /api/v1/leaves/admin/types`, `PATCH /types/{id}` |
| 정책 그룹 | `GET/POST /api/v1/leaves/admin/policies`, `GET/PATCH /policies/{id}` |
| 정책 버전 | `GET/POST .../policies/{id}/versions`, `GET/PATCH/DELETE .../versions/{vid}` |
| 활성 버전 지정 | `PUT .../policies/{id}/active-version` `{versionId}` |
| 유형별 신청 규칙 | `GET .../versions/{vid}/type-rules`, `PUT .../type-rules/{leaveTypeId}` (upsert) |
| 발생 규칙 | `GET .../versions/{vid}/accrual-rules`, `PUT`(전체 교체) |
| 사원 정책 배정 | `GET/POST /api/v1/leaves/admin/employees/{empId}/policy-assignments` |
| 사원별 잔액·발생·원장 | `GET /api/v1/leaves/admin/employees/{empId}/balances` · `/grants` · `/ledger` |
| 잔액 조정 | `POST /api/v1/leaves/admin/employees/{empId}/adjustments` + `Idempotency-Key` `{accountId, grantId, quantityDelta(분, ±), reason}` |
| 전체 신청 | `GET /api/v1/leaves/admin/requests`, `GET /admin/requests/{id}` |
| 관리자 취소 | `POST /api/v1/leaves/admin/requests/{id}/cancel` `{reason}` — 설계상 200 완료 / 202 확인 필요지만, **현재는 항상 503 `LEAVE_020`**(`LEAVE_APPROVAL_WITHDRAWAL_ENABLED=false`, 6장 #11). 버튼을 두지 않는다 |

UI 규칙:
- 휴가 유형: 사용 중인 유형은 이름·활성만 수정 가능(`LEAVE_003`) → 나머지 필드 비활성화. `balanceMode=ACCOUNT`면 잔액 유형 필수. 응답 키는 `paid`, 요청 키는 `isPaid` (이름이 다름).
- 정책 그룹 응답에 활성 버전 ID가 없다 → 버전 목록의 `isActive`로 표시.
- 활성 버전·사용 이력 있는 버전은 수정·삭제·규칙 변경 불가(`LEAVE_006`) → **읽기 전용 모드**로 열고 "새 버전 만들기" 유도.
- `fiscalStart`는 `baseType=FISCAL_YEAR`일 때만 `MM-DD`(02-29 불가). 이월 불가면 이월 한도 비움.
- 유형별 규칙 `allowedUnits`는 유형 `requestUnit`과 호환되어야 한다 (DAY→FULL, HALF_DAY→HALF, HOUR→HOUR, MIXED→전부).
- 활성화 실패(`LEAVE_008`) 메시지를 그대로 보여 준다.
- 잔액 조정은 사원별 잔액 → 발생분(`grantId`) 선택 → 분 입력 흐름. 일/시간 입력을 분으로 변환.
- 신청 목록에 날짜·사원 이름이 없다 → 사원 이름은 user-service로 조합.

### 4.4 급여 관리 (payroll)

P2. 시연용 명세서는 시드 스크립트로 이 API를 호출해 만든다.
- `POST /api/v1/pay/admin/payslips/generate` — 한 번에 1명·1개월.
- 요청에 `empName`, `deptName`(필수), `positionName`(선택, 직위 목록 API가 없으므로 비워도 됨), `hireDate`, `gradeId`를 **프론트가 채워 보내야 한다** (user-service 조회 결과로 자동 입력).
- 오류: `PAY_001`(502) 근태 연동 실패 → 재시도, `PAY_002` 직급 기본급 미등록, `PAY_003` 이미 있음. 재생성·수정·삭제·일괄 생성 없음. 명세서 목록 관리 화면도 API가 없다.

### 4.5 데이터 이관 (migration)

흐름: 작업 생성 → 파일 업로드 → 컬럼 매핑 → 검증 결과 확인·자동 정리·행 수정 → 미리보기 → 적재 → 대사 → (필요 시) 되돌리기.

**범위**: P0는 `HR_ORG`(조직 파일)과 `HR_EMPLOYEE`(사원 파일) 두 파일을 **조직 → 사원 순서로** 올려 생성 → 업로드 → 추천 매핑 확인·저장 → 이슈 `groups` 요약 → 자동 정리 → dryRun → 적재 → 대사까지다. 업로드는 파일 1개에 entity 1개다. 행 단위 PATCH와 되돌리기는 P1, 수식 규칙·LLM 요약은 P2다. 매핑 드롭다운 하드코딩도 이 두 스키마만 한다.

| 단계 | API (`/api/v1/admin/migrations`) |
|---|---|
| 작업 생성 / 상세 | `POST /` `{companyId, name, cutoverDate}`, `GET /{id}` |
| 업로드 | `POST /{id}/files` multipart `file` + `entity`(+`sheet`). xlsx/csv, 20MB. 응답에 검증 건수까지 포함(동기) |
| 파일 진단 | `GET /{id}/files/{fileId}` — 컬럼 프로파일, 매핑 추천(`suggestedBy`: SYNONYM/LLM/USER, `reason`) |
| 수식 규칙 / LLM 요약 | `GET /{id}/files/{fileId}/formulas`, `/rules/summary?refresh=` (마크다운) |
| 매핑 저장 | `PUT /{id}/files/{fileId}/mappings` |
| 오류 목록 | `GET /{id}/issues?fileId=&severity=&page=&size=` — `groups`(원인별 묶음) + `content`(행 단위) |
| 자동 정리 | `POST /{id}/fixes` (전체 또는 `fileId+code+field` 묶음) |
| 행 수정 | `PATCH /{id}/files/{fileId}/rows/{rowNo}` `{values}` |
| 미리보기 / 적재 | `POST /{id}/loads?dryRun=true|false` |
| 되돌리기 | `DELETE /{id}/batches/{batchId}` (나중 순서 묶음부터) |
| 대사 | `GET /{id}/reconciliation` |

- 심각도: `BLOCK`(적재 불가) / `WARN`(확인 필요) / `FIX`(자동 정리 제안). 적재는 BLOCK·FIX가 0건일 때만.
- `totals`·`issueTotals`는 심각도별 **이슈 건수**(행×필드)이지 행 수가 아니다. 정상 행 수 필드는 없다. 대사(`reconciliation`)는 `batchId·entity·metric`별 `source / loaded / match` 행으로 온다.
- `FIX`의 대부분(`DATE_FORMAT`, `NUMBER_FORMAT` 등)은 **규칙 기반**이다. LLM이 관여하는 것은 `ENUM_VALUE_SUGGESTED`(값 추천)와 매핑 추천(`suggestedBy=LLM`)뿐이다. AI 표식은 이 둘에만 단다.
- 실제 적재 가능한 entity는 `HR_ORG`, `HR_EMPLOYEE`뿐. 나머지는 검증만.
- 대상 필드 스키마 API가 없어 매핑 드롭다운 목록을 하드코딩해야 한다 (`EntitySchemas.java` 참조). 이슈 코드 47개의 한국어 설명은 `Backend/migration-service/README.md`.
- 작업 목록 API, 원본 행 조회 API 없음 (6장 #13). 생성한 `migrationId`는 localStorage에 최근 목록으로 저장하고 [ID로 열기] 입력을 둔다(새로고침 대비).

### 4.6 자동화 빌더 (FastAPI)

**범위**: P0는 시드된 워크플로 열기, 노드 추가·연결, 설정 패널, 검사, [저장] 버튼, 테스트 실행이다. 배포·버전 조회와 사원 노출(HOME) 토글은 P1, 입력 폼 빌더·노출 화면 탭(화면 코드 여러 개)·복제·버전 롤백 UI·자동 저장은 P2다(시연 워크플로·입력 폼·placements는 **시드 스크립트로 API를 호출해 만든다**, 재현성 확보).

- **캔버스 편집기**(React Flow): 팔레트 `GET /api/v1/ai/nodes/categories`, `GET /nodes?category=&q=`, 설정 패널은 `GET /nodes/{nodeType}`의 `configFields`로 동적 생성, 분기 노드는 `branches`별 출력 핸들 + 엣지 `branch` 라벨.
- 노드 제목·분기 라벨·카테고리는 **하드코딩하지 않고 카탈로그 응답(`name`, `branches`, `category`)을 그대로 쓴다**. 카테고리는 `input`(입력), `ai`(AI), `rule`(규칙·분기), `human`(사람 확인), `output`(문서·알림) 5종이다.
- 분기 이름은 pass/fail이 아니라 한국어 문자열이다: `llm_verify` ['자동 확정','사람 확인'], `ocr_extract`·`structured_extract` ['성공','확인 필요'], `human_confirm` ['확인','반려'], `approval` ['승인','반려'], `rag_search` ['적합','부적합','애매함']. 엣지의 `branch`가 이 값과 다르면 검사에서 이슈가 난다.
- 그래프 JSON: `{ nodes: [{id, type, position{x,y}, config}], edges: [{id, source, target, branch}] }` → `PUT /workflows/{id}/graph` (저장 시 검증 안 함).
- 검사 `POST /workflows/{id}/validate` → `issues[].nodeId/edgeId`로 캔버스 하이라이트. 서버 규칙: 노드 id 중복, 알 수 없는 노드 종류, 필수 설정 비어 있음, 없는 노드로 연결된 선, 분기 노드의 선에 분기 결과 없음, 분기 없는 노드의 나가는 선 2개 이상, 시작 노드 1개, 순환. **노드 순서(데이터 의존)는 검사하지 않는다.** '시작 노드는 1개', '순환'처럼 `nodeId`·`edgeId`가 없는 전역 이슈도 있다.
- 배포 `POST /deploy`, 버전 `GET /versions`, 롤백 `POST /versions/{v}/activate`, 복제 `POST /duplicate`(노출 화면은 복사 안 됨).
- 입력 폼 빌더 `PUT /input-form`, 노출 화면 `PUT /placements {screens: [...]}`(3.7 화면 코드 상수 사용).
- 테스트 실행 `POST /workflows/{id}/test-runs`(초안 그래프로 실행) + 실행 모니터 재사용.
- 노드 데이터는 명시적으로 연결되지 않고 "앞 노드의 같은 키"를 쓴다. 예: `structured_extract` 앞에 `ocr_extract`가 없으면 런타임에 실패한다 → 편집기에서 **엣지(위상) 기준** 순서 경고를 띄운다. 노드 위치를 옮기는 것만으로는 순서가 바뀌지 않는다.
- 노드 목록(이름): `file_upload` 파일 업로드, `employee_lookup` 사원 조회, `ocr_extract` OCR 글자 인식, `structured_extract` 서류 항목 추출(영수증), `llm_verify` 자동 검증, `rag_search` 규정 근거 검색(스텁), `human_confirm` 사람 확인, `approval` 결재 요청(목업), `approval_draft` 결재 상신(실제 결재 상신).
- `human_confirm`은 앞에 추출 노드가 없으면 `ValueError`로 FAILED가 된다. 시연 그래프에서 `ocr_extract`의 '확인 필요' 분기는 종료로 둔다.
- ⚠ 역할 검사가 없어 일반 사원도 빌더 API를 호출할 수 있다. 메뉴는 ADMIN에게만 노출하고, 백엔드 보완을 요청한다.

---

## 5. 라벨 사전 (enum → 화면 문구)

| enum | 값 → 라벨 |
|---|---|
| Role | USER 사원, ADMIN 관리자 |
| EmploymentStatus | EMPLOYED 재직, ON_LEAVE 휴직, TERMINATED 퇴직 |
| DeptType | COMPANY 회사(대표이사), DIVISION 본부(본부장), TEAM 팀(팀장) |
| EmpType | REGULAR 정규직, PARTTIME 파트타임, CONTRACT 계약직 |
| HireType | OPEN 공개채용, ROLL 수시채용, RECOMMENDATION 추천채용 |
| Gender | MALE 남, FEMALE 여 |
| FormType | LEAVE 휴가 신청, OVERTIME 초과근무 신청, TIME_FIX 근무시간 수정, HOLIDAY_SUBST 휴일대체, BUSINESS_TRIP 출장 신청, GENERAL 일반 기안 |
| DocumentStatus | INPROGRESS 진행중, APPROVED 승인, REJECTED 반려, CANCEL 회수 |
| SourceType | DIRECT 직접 기안, LEAVE 휴가, ATTENDANCE 근태, AUTOMATION 자동화 |
| LineRole | APPROVER 결재, AGREEMENT 합의, REFERENCE 참조, VIEWER 열람 |
| LineAction | PENDING 대기, AGREE 승인, DISAGREE 반려, SKIPPED 전결 생략 |
| HistoryAction | SUBMIT 상신, APPROVE 승인, REJECT 반려, FINAL_APPROVE 전결, CANCEL 회수, RESUBMIT 재기안, APPROVER_ADD 결재자 추가, APPROVER_REMOVE 결재자 제외 |
| 근무 상태 코드 | WORKING 업무, OFF_WORKING 업무종료, LEAVE 휴가, CLOCK_IN 출근, CLOCK_OUT 퇴근, CLOCK_OUT_CANCEL 퇴근취소 (`/attn/status-codes`로도 조회 가능) |
| AttendanceRequestType | TIME_FIX 근무시간 수정, OVERTIME 초과근무, HOLIDAY_SUBST 휴일대체 |
| AttendanceRequestStatus | PENDING 결재 대기, APPROVED 승인, REJECTED 반려, CANCELED 회수 |
| AnomalyType | LATE 지각, EARLY_LEAVE 조퇴, MISSING_CLOCK 출퇴근 누락, UNDER_STD_HOURS 소정근로 미달, UNAPPROVED_OT 미승인 초과근로, SHORT_BREAK 휴게 부족, WORK_ON_LEAVE 휴가 중 출근 (뒤의 둘은 현재 미판정) |
| WorkStatusSource | SELF 본인, ADMIN 관리자, APPROVAL 결재 승인 |
| LeaveRequestStatus | DRAFT 작성 중, SUBMITTING 처리 확인 중, SUBMITTED 결재 중, APPROVED 승인, REJECTED 반려, WITHDRAWN 회수, CANCELLED 취소 (관리자 취소 또는 결재 문서 생성 실패) |
| LeaveRequestPortion | FULL 종일, AM_HALF 오전 반차, PM_HALF 오후 반차 |
| LeaveType.requestUnit | DAY 일, HALF_DAY 반일, HOUR 시간, MIXED 혼합 |
| PolicyAllowedUnits | FULL 종일, HALF 반차, HOUR 시간, MIXED 혼합 |
| PolicyBaseType | HIRE_DATE 입사일 기준, FISCAL_YEAR 회계연도 기준 |
| AccrualFrequency | MONTHLY 매월, YEARLY 매년, ON_HIRE 입사 시, CUSTOM 사용자 정의 |
| GrantKind | AUTO 자동, MANUAL 수동, CARRY_OVER 이월, COMP 보상 |
| LedgerEventType | GRANT 발생, ADJUST 조정, RESERVE 예약, RELEASE 예약 해제, CONSUME 사용, RESTORE 복원, EXPIRE 소멸, REVOKE 회수, CARRY_OUT 이월 차감, CARRY_IN 이월 발생 |
| 급여 항목 | BASE_PAY 기본급, OT_EXTENDED_PAY 연장근로수당, OT_NIGHT_PAY 야간근로수당, OT_HOLIDAY_PAY 휴일근로수당, ANNUAL_LEAVE_PAY 연차수당, BONUS 상여금, HOLIDAY_BONUS 명절·휴가수당 / INCOME_TAX 근로소득세, LOCAL_INCOME_TAX 지방소득세, NATIONAL_PENSION 국민연금, HEALTH_INSURANCE 건강보험, LONG_TERM_CARE 장기요양보험, EMPLOYMENT_INSURANCE 고용보험 (응답에 `name`도 옴) |
| MigrationStatus | DRAFT 작성 중, LOADED 적재됨, COMPLETED 전환 완료 |
| BatchStatus | LOADING 적재 중, LOADED 적재됨, FAILED 실패, ROLLED_BACK 되돌림 |
| RunStatus | QUEUED 대기, RUNNING 실행 중, WAITING 확인 대기, SUCCEEDED 완료, FAILED 실패, CANCELED 취소 |
| 이관 Severity | BLOCK 적재 불가, WARN 확인 필요, FIX 자동 정리 |

- 이 장이 라벨의 **단일 원천**이다. 설계의 `statusMap.ts`는 이 라벨을 그대로 쓴다.
- **매핑되지 않은 enum 값**은 neutral 옅은 배지에 원문 코드를 그대로 표시한다(예: 판정 보류 중인 `SHORT_BREAK`, `WORK_ON_LEAVE`가 나중에 켜져도 화면이 깨지지 않게).

---

## 6. 백엔드 보완이 필요한 항목 (프론트 블로커)

우선순위: 🔴 화면을 만들 수 없음(또는 시연 블로커) / 🟠 우회 가능하지만 비용 큼 / 🟡 개선
"프론트 대응"은 설계 9장 `features.ts` 플래그와 1:1로 맞춘다. "기한"은 백엔드에 1주차에 전달하고 해소를 확인할 주차다.

| # | 서비스 | 내용 | 영향 | 프론트 대응 | 기한 |
|---|---|---|---|---|---|
| 1 | gateway | 🟡 CORS `allowedHeaders`에 `Idempotency-Key` 없음 | 같은 origin 프록시(1.2)를 쓰지 않을 때만 휴가 신청·잔액 조정 preflight 실패 | 프록시 표준 구성으로 해소. 백엔드 수정은 선택 | — |
| 2 | leave | 🔴 공휴일 조회 경로가 `/api/v1/attendance/holidays`인데 attendance는 `/api/v1/attn/holidays` (`RestHolidayClient.java:50`) | 휴가 preview가 항상 UNVERIFIABLE, 제출 503 `LEAVE_015` | preview는 실제 API로 호출하고 UNVERIFIABLE 배너로 표시 | 3주차 |
| 3 | leave | 🔴 결재 연동 설정이 모두 꺼져 있음. 실제 결재 클라이언트가 생기려면 `LEAVE_APPROVAL_ENABLED`, `LEAVE_APPROVAL_HTTP_CONTRACT_VERIFIED`, `LEAVE_APPROVAL_RESULTS_ENABLED`, `LEAVE_APPROVAL_RESULTS_CONTRACT_VERIFIED`가 모두 `true`이고 `LEAVE_APPROVAL_BASE_URL`, `KAFKA_BOOTSTRAP_SERVERS`가 있어야 한다. 회수 반영은 `LEAVE_APPROVAL_WITHDRAWAL_ENABLED`, 사원 정보는 `LEAVE_HR_ENABLED`(+HR URI·claim). leave는 config-server를 쓰지 않으므로(`spring.cloud.config.enabled=false`) compose env에 직접 넣는다 | 하나라도 빠지면 제출 503 `LEAVE_016`. 결과 수신이 꺼져 있으면 승인·반려·회수가 반영되지 않아 `SUBMITTED`에 머문다 | `FeatureGate('leaveSubmit')` | 3주차 |
| 4 | leave | 🔴 발생(grant) 생성 기능 없음, 잔액 유형 관리 API 없음 | DB에 직접 넣기 전에는 잔액 0 (홈 휴가 카드, 휴가 잔여·신청 전체) | 시드 SQL로 grant 삽입. 없으면 `FeatureGate('leaveBalance')` "발생 내역 연동 대기" | 3주차 |
| 5 | user | 🔴 직위·직급·직무코드 목록 API 없음(직책은 단건 `GET /api/v1/duties/{id}`만 있음) | 사원 등록 select 옵션만 막힘. 이름 표시는 1.4 우회 경로로 가능 | 사원 등록은 P2. 이름이 없는 곳은 `#ID` | — |
| 6 | user | 🟠 `/me` 없음 | 새로고침 시 신원 복원을 localStorage에 의존 | localStorage `employeeId` → `GET /employees/{id}` | — |
| 7 | approval | 🔴(시연) 결재 대기함·참조함·완료함 `box` 없음, 목록에 결재선·현재 결재자·`sourceType` 없음 | 홈 위젯과 시연 핵심 동선이 N+1에 의존 | 3.4 임시 계산 규칙 | 3주차까지 해소되지 않으면 N+1 유지 |
| 8 | approval | 🟠 문서 본문·첨부 필드 없음 | 일반 기안·출장 신청에 내용 입력 불가 | "속성 + 원본 링크" 카드 | — |
| 9 | attendance | 🔴 관리자 일 근태 `records`에 `logId` 없음 | 관리자 로그 수정·삭제 화면 | 관리자 근태 P2 | — |
| 10 | attendance | 🟠 관리자 일자별·기간별 현황 API 없음 | 근태 관리 목록 화면 | 관리자 근태 P2 | — |
| 11 | leave | 🟠 신청 목록에 날짜 없음, 부서 휴가 API 항상 503 `LEAVE_017`(HR 연동 off), 관리자 취소 항상 503 `LEAVE_020`(회수 연동 off) | 휴가 내역·부서 캘린더·관리자 취소 | 내역은 상세 N건 조회. 부서 휴가·관리자 취소는 만들지 않음 | — |
| 12 | payroll | 🟠 명세서 생성 시 사원 정보를 프론트가 보냄, 관리자용 명세서 목록 없음 | 급여 관리 화면 | 급여 생성 P2(시드 스크립트) | — |
| 13 | migration | 🟠 작업 목록, 원본 행 조회, 대상 필드 스키마 API 없음 | 이관 목록·행 수정·매핑 화면 | localStorage 최근 작업, 스키마 하드코딩 | — |
| 14 | ai | 🟠 업로드 파일 다운로드 없음, 빌더 역할 검사 없음, `GET /runs/{id}`·`/steps`·`/cancel`·`/runs/files/{id}`에 **소유자 검사 없음**(다른 사람 실행 조회·취소 가능), 결재 결과 수신 없음 | 사람 확인 화면 원본 이미지, 보안 | ObjectURL 세션 미리보기, 빌더 메뉴 ADMIN 전용 + 발표에서 보완 계획 명시 | — |
| 15 | 공통 | 🟡 페이지네이션·검색 없음 | 데이터가 늘면 목록 성능 | 클라이언트 페이징 | — |
| 16 | approval | 🟡 잘못된 query/path 타입이 500으로 올 수 있음 | — | ID·enum 클라이언트 사전 검증 | — |
| 17 | 공통 | 🟡 사원 이름·부서명이 응답에 없음 | — | `employees?ids=` 조합 + 캐시(`EmpName`) | — |
| 18 | ai | 🟠 `RunDetail`에 실행된 그래프 없음(`Run.graph` 컬럼은 있음, 스키마에 `graph: dict` 한 줄 추가로 해결) | 배포 후 초안을 고치면 실행 모니터 캔버스와 steps의 `nodeId`가 어긋남 | 초안 그래프 사용 + "시연 워크플로는 배포 후 수정 금지" 운영 규칙 | 4주차 |
| 19 | attendance | 🟡 근태 신청 목록에 `approvalDocId` 없음 | 목록에서 결재 문서로 바로 이동 불가 | 상세 1회 조회 후 캐시 | — |

---

## 7. 결정이 필요한 사항

1. ~~프론트 기술 스택~~ → **확정**: 설계 0·2장(React 18.3, TS 5, Vite 5, antd 6.6.8, TanStack Query 5, zustand 5, React Router 6, @xyflow/react 12). 프로젝트 중 메이저 업그레이드(React 19, Vite 6 등)는 하지 않는다.
2. ~~운영 도메인~~ → **확정**: 같은 origin(개발 Vite proxy, 운영 nginx 리버스 프록시). 1.2 참조.
3. **gateway 라우트 확인**: 외부 config 저장소의 실제 경로·StripPrefix 여부. 1주차에 프록시 연결로 확인한다.
4. ~~결재함 구현 방식~~ → **확정**: 임시 N+1(INPROGRESS 한정, 3.4 규칙). 백엔드 `box`가 생기면 훅 내부만 교체.
5. ~~명세서 출력~~ → **확정**: 브라우저 인쇄(`@media print`). PDF 다운로드는 만들지 않는다.

---

## 8. 시연 전제와 시드 체크리스트

시연 동선(설계 8.3)은 아래 데이터가 모두 있어야 끊기지 않는다. 시드는 **스크립트(API 호출, 필요 시 SQL)** 로 만들어 리허설마다 다시 돌릴 수 있게 한다. 리허설 전날 아래 "확인" 열을 모두 점검한다.

| # | 항목 | 왜 필요한가 | 확인 |
|---|---|---|---|
| 1 | user1~user4가 속한 팀의 `deptType=TEAM`, 부서장 user2. 상위 본부(`DIVISION`)와 회사(`COMPANY`)에도 유형·부서장 | 없으면 모든 상신이 `APPROVAL_022` | `GET /employees/{user1}/org-chain`의 `chain[].headEmpId`가 모두 채워짐 |
| 2 | 시연 사원의 그 달 초과근무 누적 12시간 미만 | 넘으면 OVERTIME이 본부장 전결이 되어 팀장 승인만으로 문서가 끝나지 않는다 | `GET /documents/approval-line-preview?formType=OVERTIME...` 결재자 1명 |
| 3 | 시연 사원의 **전날** 출퇴근 로그(08:52 출근 · 18:32 퇴근) | 낮 시연에서 당일 퇴근으로는 미승인 연장이 생기지 않고, 출근은 하루 1회라 리허설 반복이 안 된다 | `POST /attn/admin/logs`로 등록 후 `GET /attn/me/daily/{어제}`의 `unapprovedOtMin > 0` |
| 4 | 리허설 계정 로테이션표(user1 → user3 → user4 …, 모두 #1의 팀 소속) | 한 계정은 같은 날 같은 신청을 반복할 수 없다 | 표를 대본에 붙임 |
| 5 | `pay_grade` 직급 기본급, 시연 사원 명세서 1~2개월(P1 화면용) | 없으면 `PAY_002`, 명세서 빈 화면 | `GET /pay/me/contract` 200 |
| 6 | 휴가 정책 배정과 grant(휴가를 보여 줄 경우만) | 없으면 잔액 0 | `GET /leaves/me/summary` 잔액 > 0 |
| 7 | 공휴일 데이터 | 캘린더 표시 | `GET /attn/holidays` |
| 8 | 시연 워크플로("영수증 경비 정산") 그래프·입력 폼·placements(`HOME`) 생성과 배포. 결재 상신 금액은 100만 원 이하 | 빌더 화면 없이도 재현 가능해야 한다. 100만 원 초과면 본부장 결재가 붙는다 | `GET /ai/workflows/available?screen=HOME` |
| 9 | 데모 8번용 "검사 실패" 그래프(별도 워크플로로 저장) | 무대에서 편집 실수 없이 이슈를 보여 준다 | `POST /validate`의 issues가 2건 이상 |
| 10 | 검증된 영수증: A(OCR 신뢰도는 통과, GMS와 금액만 다름 → 사람 확인), B(자동 확정) | 사람 확인 분기는 두 LLM 결과가 달라야 하므로 비결정적이다 | 리허설마다 A가 '사람 확인'으로 가는지 확인. 실패하면 노드 config `min_confidence: 0.99`로 강제(이때 교차 추출 열은 없음) |
| 11 | 이관 파일 2개: 조직.xlsx(HR_ORG 32행), 사원.xlsx(HR_EMPLOYEE 1,252행), BLOCK 0~1건 | 9분 대본 안에 적재까지 끝내야 한다 | dryRun 결과 |
| 12 | Kafka 브로커 기동, approval 아웃박스 → attendance 리스너 E2E | 데모 7번 | 4주차 말 점검: 초과근무 승인 후 신청 `APPROVED` |

---

## 검토 반영 이력 (2026-11-04)

출처: A = A-fe-lead 검토, B = B-integration 검토, 공통 = 두 검토자가 실질적으로 같은 문제를 지적. 단독 지적은 코드를 직접 열어 확인한 뒤 반영했다.

| # | 지적 | 출처 | 조치 |
|---|---|---|---|
| 1 | 화면 약 45개에 우선순위가 없어 6주·2명으로 무엇을 버릴지 판단할 수 없음 | 공통(A R1, B R4) | 2장 메뉴에 P0/P1/P2와 기준 추가. 4.2 근태 관리·4.3 휴가 관리·4.4 급여 관리 P2, 4.5·4.6에 P0 범위 명시 |
| 2 | Idempotency-Key CORS·SameSite를 백엔드 블로커로 둠. 같은 origin 프록시면 해소 | 공통(A R2, B R2) | 1.2에 표준 구성(Vite proxy, nginx) 확정, 6장 #1 🟡, 7장 #2 종결. Vite `changeOrigin`은 Host만 바꾸므로 `Origin` 헤더 제거를 추가(LAN 대비, 1주차 실측) |
| 3 | 휴가 결재 연동 블로커(#3)를 한 줄로 축소 | 공통(A R3, B R1) | `LeaveApprovalConfiguration`·`application.yml` 확인 후 필요한 설정 6개와 회수·HR 플래그, compose env 직접 주입을 6장 #3에 기술. 3.5 회수 반영 조건 추가 |
| 4 | `PAY_002`를 "다시 로그인"으로 해석한 오류, `/contract`는 개인 계약이 아님 | 공통(A R4, B R11) | 3.6을 "내 직급 급여 정보"로 바꾸고 message 그대로 표시. 4.4에 PAY_001/002/003, positionName 선택 입력 |
| 5 | 결재 대기함 N+1의 범위·동시성·캐시 기준 없음, 우선순위 낮음 | 공통(A R5, B R8) | 3.4에 임시 계산 규칙(INPROGRESS, drafter≠me, 최근 50건, 동시 4, staleTime 30초, 관리 모드 카운터 끔). 6장 #7 🔴(시연). 탭은 결재 대기·기안함·전체만 P0 |
| 6 | CANCELLED 의미 누락(결재 문서 생성 실패), DRAFT 라벨 누락 | A(R6) | `LeaveSubmissionResponse`·`LeaveRequestStatus` 확인 후 3.5·5장 수정 |
| 7 | 부서명·직책명 우회 API 누락, #5 과대평가, 사원용 조직도 범위 | 공통(A R7, B R7, A 교차 F19) | 1.4에 org-chain·`/duties/{id}`·로그인 응답 우회 기술, 6장 #5 축소, 조직도는 결재 라인 + 사원 검색(P1) |
| 8 | 자동화 빌더 범위 과다 | 공통(A R8, B R4) | 4.6 P0를 시드 워크플로 열기·노드 추가·연결·설정·검사·저장·테스트 실행으로 한정, 나머지 P1/P2와 시드 스크립트 |
| 9 | 이관 9단계 전부 요구, 새로고침 시 migrationId 유실 | 공통(A R9, B R4) | 4.5 P0 범위(HR_ORG·HR_EMPLOYEE 2파일) 명시, localStorage 최근 목록 |
| 10 | 결재선 편집 규칙 6개를 클라이언트에서 재구현 | 공통(A R10, B R13) | 클라이언트 규칙 2개 + 서버 오류 토스트. 범위 충돌(A: 참조자 추가만, B: 참조·열람·결재자 추가)은 더 작은 A안 채택, P1 |
| 11 | 시연 데이터 전제·시나리오 없음 | 공통(A R11, B R12) | 8장 "시연 전제와 시드 체크리스트" 신설(12항목, 확인 API 포함) |
| 12 | `crypto.randomUUID`는 secure context 전용 | A(R12) | 1.4 멱등 키를 `uuid` v4 + 성공 후 재생성 규칙으로 수정 |
| 13 | 재조회 간격·실행 모니터 중단 조건 미정 | A(R13) | 1.4·3.7에 React Query 기본값과 중단 조건 명시(설계 7.3과 같은 값) |
| 14 | `year` 필수, `{documents,totalCount}` 래퍼, 실행 취소·파일 API 누락, 목록 래핑·ids 규칙 | 공통(A R14, B R9·R14) | 3.3·3.4·3.7·1.4 보완(코드 확인) |
| 15 | 7장 "결정 필요"가 설계에서 이미 확정, 블로커 표와 FeatureGate 불일치 | A(R15) | 7장 #1·#2·#4·#5 확정 처리, 6장 표에 "프론트 대응"·"기한" 열 추가. 메이저 업그레이드 금지 명시 |
| 16 | 날짜 라이브러리 미정 | A(R16) | 1.4에 dayjs + utc/timezone, `src/format/` 단일화 |
| 17 | 401 인터셉터 제외 대상·다중 탭 reissue 경합 | B(R3) | user-service `ErrorCode` 확인(AUTH_005/007/008 모두 401). 1.1에 제외 규칙과 단일 탭 전제 추가. `navigator.locks` 직렬화는 P2(보수적 버전) |
| 18 | 관리자 취소가 4.3(200/202)과 6장(503) 사이에서 충돌, 부서 휴가 503 원인 코드 누락 | B(R5) | `LEAVE_017`·`LEAVE_020` 확인 후 4.3·1.5·6장 #11 정리, 버튼 없음 |
| 19 | 전결 임계값을 사실처럼 하드코딩 | B(R6) | `ApprovalPolicy`가 properties를 읽는 것 확인. "설정 예시(미검증)" 표기 + 하드코딩 금지 규칙 |
| 20 | 화면 코드 상수 미정, 실행·파일 API 소유자 검사 없음 | B(R9) | 3.7에 `SCREEN_CODES`, 6장 #14에 소유자 검사 부재 추가(`runs/routers.py` 확인) |
| 21 | 휴가 클라이언트 사전 규칙이 preview와 중복 | B(R10) | 3.5 클라이언트 규칙을 4개로 축소 |
| 22 | 실행 모니터가 실행된 그래프를 받을 API 없음 | B(교차 F3) | `RunDetail` 스키마 확인. 6장 #18 신설, 3.7에 운영 규칙 |
| 23 | 근태 신청 목록에 `approvalDocId` 없음, 근태 회수는 동기 | B(교차 F8·F9) | `AttendanceRequestSummaryResponse`·`cancel()` 확인. 3.3 보완, 6장 #19 |
| 24 | HITL 필드는 3개, 신뢰도 미달이면 GMS 교차 추출 생략, 기준 0.9/0.8 | 공통(A 교차 F01, B 디자인 F01) | `receipt.py`·`llm_verify.py` 확인. 3.7 6번에 기술 |
| 25 | 분기명·카테고리·노드명이 FastAPI와 다름 | 공통(A F3·F02, B F02·F4·F5) | 카테고리 5종(`rule` 포함, `registry.py` 확인). A 디자인 검토의 "4종"은 코드와 달라 기각. 4.6에 실제 값 기술 |
| 26 | 서버 validate는 순서를 검사하지 않음, 전역 이슈 존재 | 공통(A F9·F03, B F03·F20) | `validator.py` 확인. 4.6 규칙 목록 정정 |
| 27 | HITL 반려 시 run은 SUCCEEDED | B(디자인 F15) | `engine/builder.py`·`human_confirm.py` 확인. 3.7에 최종 표시 계산 규칙 |
| 28 | FIX 대부분은 규칙 기반, LLM은 `ENUM_VALUE_SUGGESTED`뿐 | B(교차 F6) | `FieldValueChecker`·`ValidationService` 확인. 4.5에 기술 |
| 29 | 이관 totals는 행 수가 아니라 이슈 건수 | B(디자인 F11) | `IssueListResponse`·`FixApplyResponse` 확인. 4.5에 기술 |
| 30 | 라벨 불일치(CANCELLED, WARN), 미매핑 enum 처리 | 공통(A F13, B F18·F22) | 5장을 라벨 단일 원천으로 지정, Severity 행·fallback 규칙 추가 |
| 기각 | 다중 탭 `navigator.locks` 직렬화 필수화 | B(R3 일부) | 6주 범위에 비해 과하다. 단일 탭 전제로 대신하고 P2 |
