# Leave API 사용자 여정 테스트

Postman에서 호출하던 순서를 `leave_api_test.py`의 `user_journey()`에 작성했다.
실행 엔진·Catalog 모듈·Fixture JSON·Resume·VU·부하 테스트는 없다. 한 번 실행하고 `result/result_verN.json`을 남긴다.

## 실행

```powershell
cd Backend/leave-service/tools/api-test
python -m pip install -r requirements.txt
Copy-Item .env.example .env
# .env에 주소·계정 비밀번호·실제 전용 직원 ID를 입력
python leave_api_test.py
```

`.env`는 스크립트와 같은 폴더에서 읽는다. 같은 이름의 셸 환경변수가 있으면 그 값이 우선한다.
비밀번호에 `#`, `=`, `$`가 있어도 그대로 읽으며 셸 코드를 실행하지 않는다. 줄 끝 주석은 쓰지 않는다.
Python 3.11 이상을 사용한다. 결과 파일과 `.env`는 Git에서 제외한다.

### 필수 설정

```dotenv
BASE_URL=https://nexa-erp.duckdns.org
ENVIRONMENT=ec2-dev
ADMIN_EMAIL=leaveadmin@local.dev
ADMIN_PASS=
ORDINARY_EMAIL=
ORDINARY_PASS=
TEAMLEAD_EMAIL=
TEAMLEAD_PASS=
APPROVER_EMAIL=
APPROVER_PASS=
ALLOW_WRITES=false
DEDICATED_EMPLOYEE_CONFIRMED=false
EXPECTED_EMPLOYEE_ID=
DATA_KEY=LEAVE_TEST
BALANCE_TYPE_ID=
ACCOUNT_ID=
GRANT_ID=
INITIAL_GRANT_ENTRY_ID=
INTERNAL_LEAVE_URL=
```

- 실제 전용 직원과 개발 환경임을 확인한 뒤 쓰기 허용 두 값을 모두 `true`로 바꾼다.
- `EXPECTED_EMPLOYEE_ID`는 일반 직원 로그인 응답과 최신 User 정보의 ID와 같아야 한다.
- `ADMIN`은 최신 직책명이 정확히 `휴가관리자`인 계정이다. 시스템 Role로 판단하지 않는다.
- `TEAMLEAD`는 조회 부서의 실제 책임자, `APPROVER`는 실제 문서 결재선의 직원이다. 같은 사람이면 동일 계정을 입력해도 된다.
- 추가 상위 결재자가 있으면 `APPROVER2_EMAIL/PASS`를 입력한다. 없으면 비워 둔다.
- `INTERNAL_LEAVE_URL`은 익명 GET으로 접근할 수 있는 Leave 직접 주소/SSH 터널 주소다. Gateway가 내부 API 익명 요청까지 허용한다고 가정하지 않는다.
- HTTPS를 사용하고 TLS 인증서 검증을 끄지 않는다. 로컬 Mock 서버에만 HTTP를 허용한다.
- 기본값은 쓰기 비활성이다. 로그인과 GET을 확인할 수 있고 나머지는 SKIP/BLOCKED로 표시한다.

로그인은 실제 계약인 `POST /api/v1/auth/login`, `{"email":"...","pass":"..."}`를 사용한다.
응답의 `accessToken` 쿠키를 계정별 `requests.Session`이 재사용한다. 토큰을 JSON 응답이나 Authorization에서 추측하지 않는다.
자동 로그인 재시도·토큰 갱신·다른 계정의 세션 공유는 하지 않는다.

## 호출 순서

1. 관리자·일반 직원·팀장·결재자 로그인, 최신 직원·직책 확인
2. 유형·배정 이력·신청 목록, 일반 직원 관리자 접근 거부 확인
3. 실행 전용 유형 생성 → 이름 수정 → 목록 반영 확인
4. 실행 전용 정책 그룹 생성 → 이름 수정 → 목록·상세 조회
5. 버전 생성 → 수정 → 발생 규칙 PUT/GET → 유형 규칙 PUT/GET → 활성화
6. 새로 만든 별도 미사용 버전만 DELETE, 상세 404 확인
7. 최초 직원 정책 배정 또는 기존 배정 충돌 거부·보존 확인
8. 본인 정책·신청 가능 유형·잔액·summary·발생분·원장, 관리자 조회
9. 정상 초기 지급이 있으면 관리자 ADJUST로 **30분 감소 한 번** 검증
10. 신청 Preview와 실제 신청: 전일 승인, 오전 반차 반려, 오후 반차 기안자 회수 각각 한 건
11. 실제 결재 문서·기안자·sourceId·UUID 조회 → 결재자 처리 → Kafka 정산 폴링 → 원장·잔액 변화 → Attendance 조회
12. 신청 상세·내부 날짜·관리자 취소 503 차단·부서 일정·부서 잔액

Controller의 전체 39개 API(GET 24, POST 8, PATCH 3, PUT 3, DELETE 1)를 결과에 표시한다.
API별 기본 검증에 더해 저장 결과 확인용 GET, 미사용 삭제 대상 생성, 승인/반려/회수 세 건에 필요한 추가 호출이 있다.
같은 사용자 여정을 반복하거나 부하를 발생시키는 루프는 없다. 비동기 결과 조회만 제한 시간 내 폴링한다.

기존 배정이 있는 직원에게 매 실행마다 새 정책을 덮어쓰지 않는다.
전용 정책에 배정된 경우 같은 `effectiveFrom`으로 POST하여 **409/LEAVE_009 거부와 이력 보존**을 검증한다.
이는 정상 신규 배정 성공을 다시 검증했다는 뜻이 아니다. 기존 배정이 없을 때만 새 정책에 최초 배정한다.
과거/미래 배정만 있거나 다른 업무 정책에 배정되어 있으면 변경하지 않고 BLOCKED로 표시한다.

## 최초 데이터 준비

API로 만들 수 없는 잔액 유형, 직원 계정, MANUAL 발생분, 최초 GRANT만 SQL로 준비한다.
스크립트가 SQL을 실행하거나 DB에 직접 연결하지 않는다. 기존 두 SQL의 잠금·검증·중복 지급 방지는 유지했다.

### 1. 잔액 유형

승인된 **개발 DB**에서 수동 실행한다. DB 비밀번호는 명령행에 넣지 않는다.

```bash
psql '<개발 DB 연결 정보>' \
  -v expected_database=nexa_leave -v fixture_key=LEAVE_TEST \
  -v allow_dev_write=YES -f init/01_balance_type.sql
```

출력 `balanceTypeId`를 `.env`의 `BALANCE_TYPE_ID`에 입력한다.
이름은 `E2E_<DATA_KEY>_ANNUAL_BALANCE`다. 재실행은 `-v expected_balance_type_id=<기존 실제 ID>`를 추가한다.
SQL의 `fixture_key`는 단순한 **고정 지급 식별자**이며 `.env DATA_KEY`와 같다. Fixture 파일을 관리하지 않는다.

### 2. 정책·직원 배정 API

계정·전용 직원·쓰기 허용을 설정하고 `python leave_api_test.py`를 실행한다.
기존 배정이 없다면 새 정책 버전과 규칙을 만들고 활성화·직원 배정을 진행한다.
최초 지급이 없으면 ADJUST·ACCOUNT 정산은 BLOCKED다. NONE 유형은 잔액 없이 별도 결재 흐름을 검증할 수 있다.
최신 `result/result_verN.json`의 `initialGrantParameters`에서 **직원에게 실제 적용된** 정책 ID·버전 ID·이름을 확인한다.
`createdIds`는 그 실행의 CRUD 데이터이므로, 재실행에서 새로 만든 미배정 정책 ID를 최초 지급에 쓰면 안 된다.

### 3. 최초 GRANT

발생분 유효기간은 테스트할 미래 날짜를 포함해야 한다. 한 번 정한 지급 시각·수량·DATA_KEY를 재실행 때 바꾸지 않는다.

```bash
psql '<승인된 개발 DB 연결 정보>' \
  -v expected_database=nexa_leave -v fixture_key=LEAVE_TEST \
  -v allow_dev_write=YES -v dedicated_employee_confirmed=YES \
  -v employee_id='<실제 전용 직원 ID>' -v balance_type_id='<SQL 01 ID>' \
  -v policy_id='<initialGrantParameters.policyId>' \
  -v version_id='<initialGrantParameters.versionId>' \
  -v expected_policy_name='<initialGrantParameters.expectedPolicyName>' \
  -v minutes=7200 -v valid_from='<유효 시작 ISO 시각>' \
  -v expires_on='<유효 종료 ISO 시각>' -v credit_at='<최초 지급 ISO 시각>' \
  -f init/02_initial_grant.sql
```

계정·발생분·GRANT를 한 트랜잭션으로 생성하고 실제 `accountId/grantId/initialGrantEntryId`를 출력한다.
이 세 값을 `.env`에 넣고 다시 **같은 명령** `python leave_api_test.py`를 실행한다.
이후에는 초기 SQL을 다시 실행할 필요가 없다. 7200분은 테스트 예산이며 자동 연차 산정 결과가 아니다.
기존 최초 지급 키가 있으면 속성·역분개·현재 합계를 검증하며 **추가 지급하거나 잔액을 초기화하지 않는다.**
실패 시 `ON_ERROR_STOP`으로 중단하며 `psql -f` 연결 종료 시 미커밋 작업이 롤백된다. 대화형 세션에서 실행했다면 오류 후 `ROLLBACK`한다.

## 공용 서버에서 남는 데이터와 실패 처리

- 실행별 UUID가 포함된 이름·Idempotency-Key를 사용한다. 기준 GRANT의 DATA_KEY는 고정한다.
- 기존 정책·유형·직원 배정·원장을 UPDATE/DELETE하지 않는다. 새 미사용 버전의 DELETE API만 검증한다.
- 실행마다 전용 유형·그룹·버전과 신청·결재·근태 이력이 남는다. cleanup/DB 초기화 기능은 없다.
- 정상 ACCOUNT 실행은 ADJUST 30분 + 승인 전일 480분, **총 510분**이 줄어든다. 반려/회수는 예약을 해제한다.
- 가용 잔액이 부족하면 현재 정책의 NONE 유형을 사용한다. 추가 GRANT를 만들거나 다른 직원 계정을 빌리지 않는다.
- 진행 중인 기존 신청이 있으면 배정·조정·새 신청을 차단한다. 새 신청 결과가 불명확하면 다음 신청을 보내지 않는다.
- POST 응답 유실·Timeout은 자동 재전송하지 않는다. 결과에 남은 runName·Idempotency-Key·신청 ID로 서버를 수동 확인한 뒤 새 실행 여부를 판단한다.
- 자동 재개/복구가 없으므로 실패한 실행을 처음부터 무조건 재실행하지 않는다.
- 실제 결재자 계정이 없거나 정산이 늦으면 해당 연동은 BLOCKED다. 독립적인 Leave 조회는 계속한다.
- Attendance SUBSTITUTE 응답은 기존 계약 차이로 BLOCKED를 기록한다. 해당 날짜만 피해 통과시키지 않는다.
- 관리자 직접 취소는 기본값 TEST_ADMIN_CANCEL_ENABLED=false에서 **503/LEAVE_025 차단**을 검증한다. true에서는 아래 활성 시나리오를 실행한다.
- 내부 날짜 URL이 없으면 해당 API만 BLOCKED다. Attendance 월간 검증은 별도로 수행한다.
- 직원 전용 사용 확인은 운영 약속이다. 여러 호스트의 동시 실행을 막는 분산 잠금이나 분산 스냅샷은 제공하지 않는다.

## 결과와 로컬 검증

실행 후 `result/result_verN.json`에 API 39개의 PASS/FAIL/SKIP/BLOCKED, 실패 사유, HTTP 상태·경로·시간, 생성 ID를 저장한다.
`integrations`에서 승인·반려·회수의 Approval/Kafka/Attendance 결과를 따로 확인한다.
실행마다 기존 최대 번호 다음의 새 파일을 만들므로 이전 결과는 덮어쓰지 않는다. 비밀번호·이메일·JWT·쿠키·원격 응답 본문은 저장하지 않는다.
종료 코드: 0=완료, 1=FAIL, 2=BLOCKED. API 표가 PASS여도 외부 연동 BLOCKED가 있으면 전체 완료가 아니다.

```powershell
# 실제 EC2를 호출하지 않는 로컬 Mock Gateway 테스트
python -m unittest discover -s tests -p test_leave_api_test.py -v

# 선택: 이전 SQL 안전성 회귀, 새 격리 컨테이너만 사용
$env:E2E_SQL_TESTS='1'
$env:E2E_POSTGRES_IMAGE='postgres:17-alpine'
python -m unittest discover -s tests -v
```

Mock은 로그인 JSON·Set-Cookie·계정별 인증·39개 경로·ID 전달·CRUD 반영·승인/반려/회수 정산·실패 후 독립 조회·비밀정보 비저장을 검증한다.
실제 EC2·Kafka 브로커·다른 서비스 연동 성공을 의미하지 않는다. Java 운영 코드와 Flyway는 변경하지 않았다.

2026-11-05 로컬 결과: **35개 통과, 실패·오류·스킵 0개**.
Mock HTTP/단위 24개와 격리 PostgreSQL 17 SQL 회귀 11개다. 실제 EC2에는 요청하지 않았다.

## Attendance 관리자 취소 활성 검증

서버의 Attendance 원복 연동이 준비된 전용 개발 환경에서만 `.env`에 아래 값을 설정한다.

```dotenv
TEST_ADMIN_CANCEL_ENABLED=true
```

기존 ALLOW_WRITES=true, DEDICATED_EMPLOYEE_CONFIRMED=true, 실제 EXPECTED_EMPLOYEE_ID 확인도 유지한다.
이 값은 Python 테스트 시나리오 선택일 뿐 서버 기능 플래그를 변경하지 않는다.

기존 승인·반려·회수와 부서 조회를 마친 뒤 이번 실행에서 승인한 신청 한 건만 관리자 취소한다.
근태 월간 조회의 반영 해제, Leave CANCELLED/COMPLETED, 원래 CONSUME별 RESTORE,
Approval 문서 APPROVED 유지, 완료된 동일 요청 재호출 시 추가 RESTORE가 없음을 확인한다.
진행 중/UNKNOWN 응답은 BLOCKED로 기록하고 쓰기 재시도하지 않는다.
취소 UUID는 result의 integrations.admin_cancel 시나리오에 기록한다.
정상 ACCOUNT 실행에서 취소까지 완료되면 기존 510분 감소 중 승인 480분이 복원되어 총 30분 감소한다.
NONE 유형은 원장 없는 상태 취소를 검증한다.

과거 APPROVED Kafka 이벤트를 실제 브로커에 다시 발행하는 기능은 추가하지 않았다.
Attendance 취소 이력·재반영 방지는 담당자와 별도의 통제된 E2E로 확인해야 한다.
실제 EC2 쓰기는 이 구현 작업에서 실행하지 않았다.
