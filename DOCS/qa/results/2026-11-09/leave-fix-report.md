# 휴가 QA 수정 결과 — 2026-11-09 QA

## 작업 요약

| 항목 | 결과 |
|---|---|
| QA 수행일 | 2026-11-09 (후속 기록은 11-10 포함) |
| 수정 작업일 | 2026-11-10 |
| QA 기준 커밋 | `dab4467` |
| 수정 시작 커밋 | `ca40622` |
| 시작 작업 트리 | 변경 없음 |
| 분석 대상 | 6개 그룹: 최종 목록 5개 + 원본에만 남은 LV-06 |
| 실제 수정 | 5개 그룹 |
| 그룹별 상태 | FIXED_VERIFIED 4 / FIXED_UNVERIFIED 1 / POLICY_PENDING 1 / BLOCKED 0 |
| 미검증 그룹 | LV-C2: 개별 기능 테스트는 통과했으나 실제 브라우저 대량 날짜 성능은 미검증 |
| 정책 확인 대기 | LV-C1: 기존 날짜 단위 충돌 규칙 유지 |
| Backend 변경 | 12개 파일: 운영 코드 7, 테스트 5 |
| Frontend 변경 | 15개 파일: 화면·Hook·유틸리티 10, 패키지 2, 테스트 3 |
| QA 문서 | 본 보고서 1개 신규. 기존 QA 원본·버그 목록 보존 |
| Backend 검증 | 105개 테스트 클래스의 2,151개 실행, 실패·오류·스킵 0, bootJar 성공 |
| Frontend 검증 | 휴가 회귀 테스트 26개 PASS, 타입 검사·프로덕션 빌드 PASS |
| 실제 EC2 검증 | 미실행. 공용 서버 쓰기·장애 주입·DB 변경 없음 |

`FIXED_VERIFIED`는 이 문서에 명시한 로컬 단위/격리 통합 테스트 범위의 검증을 뜻한다. 실제 EC2 운영 정상 동작이나 Ant Design의 브라우저 레이아웃·성능을 보장하는 표현은 아니다.

### 기준 코드와 규칙

- QA 기준과 수정 시작 커밋의 `Backend/leave-service`, `Frontend/src/leave`, 휴가 API·포맷·페이지·관련 문서 Hook·AsyncSync에는 차이가 없었다.
- 현재 저장소에서 관련 AGENTS.md는 확인되지 않았다. 복원하거나 새로 생성하지 않았다.
- Approval·Attendance는 계약 확인을 위해 읽기만 했다. 다른 MSA·Infra·Config·기존 Flyway는 수정하지 않았다.
- 기존 ApiResult, 인증·관리자 직책 정책, 공개 JSON, T1의 배분·예약 및 원장 delta 계산은 유지했다.
- Git commit/push, EC2 배포, 공용 DB 초기화는 수행하지 않았다.

## [LV-B1 / F18 / LV-54] 결재 장애 후 SUBMITTING 및 예약 고착

### 1. QA 문제

- 원본: `14-rerun-round2.md:394~396`, `bugs-by-service.md:55`.
- Approval 중지 → 휴가 신청 → T1과 RESERVE 커밋 → UNKNOWN → Approval 재기동 → 같은 요청 재확인.
- 실제: 결재 문서가 처음부터 만들어지지 않았으면 조회만 반복해 SUBMITTING과 480분 예약이 남았다.
- 기대: UUID·신청·예약을 유지하면서 기존 문서를 연결하거나 안전하게 생성 재개.

### 2. 원인 및 계약 근거

`LeaveSubmissionOrchestrationService.submit()`은 UNKNOWN/IN_PROGRESS 재요청을 `ApprovalClient.recover()`의 UUID 조회로만 처리했다. `startCall()`은 NOT_SENT만 허용하므로 미생성 상태에서 다음 생성 단계로 갈 수 없었다.

다른 서비스에서 읽기 확인한 근거:

- `Backend/approval-service/.../document/service/DocumentServiceImplV1.java#create`: 같은 requestKey의 Advisory Lock 획득 → 기존 문서 반환 → 없을 때 생성·상신.
- `toCreateResponse()`: 기존 문서와 기안자·formType·sourceType·sourceId 불일치 시 409.
- `Backend/approval-service/.../db/migration/V6__approval_document_request_key.sql`: request_key UNIQUE.
- `findByRequestKey()` → `getReadableDocument()`: 없음과 권한 없음 모두 404가 될 수 있다.

따라서 404는 **부재 확정이 아니며 RELEASE 근거도 아니다**. 다만 위 멱등 생성 계약이 확인된 HTTP Adapter에서 동일 UUID로 POST를 재개할 수 있다.

QA의 “프론트 재확인 버튼은 GET만 호출”이라는 설명은 현재 소스와 다르다. 기존 `Apply.send()`와 `useSubmitLeave()`는 이미 같은 키의 POST였다. 이번 작업에서 버튼을 GET에서 POST로 바꿨다고 기록하지 않는다. 당시 번들/Network 기록은 별도 확인 필요하다.

### 3. 실제 수정과 선택 이유

1. 기존 T1이 직원·입력·submissionKey·payload hash를 확인하고 기존 신청을 반환한다. 신규 신청·예약을 다시 만들지 않는다.
2. 기존 UUID로 Approval GET 조회를 먼저 수행한다.
3. 404는 내부 결과 `LOOKUP_NOT_FOUND`로 구분한다. 공개 신청 상태나 오류 JSON을 추가한 것이 아니다.
4. `supportsIdempotentResume()`를 명시적으로 지원하는 Adapter만 생성 재개를 허용한다. 기본 구현과 미연동 Client는 false다.
5. `startRecoveryCall()`이 직원 Advisory Lock과 신청 행 잠금 후 최신 상태를 확인한다. UNKNOWN 또는 30초 이상 남은 IN_PROGRESS만 재개할 수 있다.
6. 기존 attempt_count·status_changed_at을 사용해 IN_PROGRESS와 새 시도 번호를 짧은 REQUIRES_NEW 트랜잭션으로 커밋한다. 새 DB 컬럼은 없다.
7. 저장된 신청 상세로 ApprovalCreateCommand를 만든다. UUID·sourceId·직원·날짜·사유는 재요청 Body로 교체하지 않는다.
8. DB 트랜잭션 밖에서 같은 UUID의 생성 POST를 수행한다.
9. 재개 POST의 간략 documentId만으로 연결하지 않는다. UUID GET으로 documentId·기안자·sourceId·sourceType·formType·결재 상태를 검증한다.
10. 검증된 문서만 기존 T2로 연결한다. 실패·Timeout·미확인 응답이면 UNKNOWN과 예약을 유지한다.
11. 시도 번호를 비교하여 늦게 도착한 이전 HTTP 실패가 새로운 IN_PROGRESS를 덮어쓰지 않도록 한다. 이미 연결된 결과도 보존한다.

**30초는 호출권 재획득 기준이며 미생성 확정·예약 만료 기준이 아니다.** 서버 종료 후 남은 IN_PROGRESS도 재조회/재개할 수 있게 한다. 30초를 넘는 이전 원격 호출이 아직 살아 있다면 HTTP가 겹칠 수 있으나, 원격의 동일 UUID 잠금·UNIQUE가 중복 문서 생성을 막는 전제다. 동일 UUID 동시 생성 계약이 배포 버전에도 반드시 있어야 한다.

조회 401/403·5xx·Timeout에는 생성 재개를 하지 않는다. 권한 은닉 404 뒤 POST가 409/403이 되거나 후속 신원 검증이 실패하면 성공·미생성으로 처리하지 않는다. 시간 경과만의 FAILED·RELEASE는 없다. 기존 명시적 `NOT_CREATED_CONFIRMED` 보상 계약은 유지한다.

### 4. 수정 파일·핵심 메서드

- `ApprovalClient`: Adapter별 멱등 재개 지원과 `resume()` 경계.
- `ApprovalCreationResult`: 내부 조회 404 구분.
- `ApprovalHttpClient.resume/recover`: 동일 UUID POST 및 원격 문서 검증.
- `LeaveApprovalCallStartResult`: 시도 번호 전달. 기존 2인자 생성자 유지.
- `LeaveRequest.resumeApprovalCall/beginApprovalAttempt`: 기존 필드로 재개 전이.
- `LeaveApprovalResultService.startRecoveryCall/recordUnknownAfterHttp`: 호출권 경합·오래된 실패 차단.
- `LeaveSubmissionOrchestrationService.recoverSubmission/callApproval`: 조회·로컬 재개 커밋·외부 호출·T2 연결 분리.

### 5. 검증

- 단위/Mock HTTP: 404 결과 구분, 동일 UUID·저장된 Body 사용, 원격 기안자 불일치 거부, 401/403/409/503, 재개 권한 없는 호출의 POST 금지, 이전 시도 실패 차단.
- PostgreSQL: 두 동시 재개 중 한 요청만 호출권 획득, attempt 증가, UUID 유지, RESERVE 1개·RELEASE 0개.
- PostgreSQL + 실제 로컬 HTTP + Kafka: 최초 503 미생성 → 재개 → 문서 1개·예약 1개 → APPROVED CONSUME 정산.
- 생성 응답 유실 → UUID 복구, 조기 APPROVED 이벤트 DLT 이동 → 문서 연결 후 원본 이벤트 수동 재전달 → 정산 검증.
- 조회 권한 실패 중 예약 유지, 이후 정상 조회 연결 및 반려 RELEASE 검증.
- 전체 2,151개 회귀 테스트에서 다른 Body 충돌, 본인 인증, T1/T2/T3, 기존 회계·취소 계약 포함 통과.

### 6. 제한과 운영 확인

- DLT에 간 이벤트를 문서 연결만으로 자동 정산하지 않는다. 자동 재전달/스케줄러는 추가하지 않았다.
- HTTP 상태 조회가 APPROVED라고 해서 HTTP Client가 직접 CONSUME하지 않는다. 최종 정산 책임은 기존 Kafka 경로에 있다.
- `ApprovalLeaveFlowIntegrationTest`는 **테스트 대역 HTTP 서버**의 멱등 계약으로 검증한다. 실제 Approval 서버를 실행한 교차 서비스 E2E는 아니다. 실제 계약은 다른 서비스 소스 읽기로 대조했다.
- 기존 연동 활성화·HTTP/Kafka 계약 확인 플래그를 유지했다. 새 환경변수는 없다.
- 운영 적용 전 동일 UUID 계약이 반영된 Approval 배포 버전과 DLT 수동 복구 절차를 확인해야 한다.

최종 상태: **FIXED_VERIFIED** (격리 테스트 범위, EC2 미검증).

## [LV-B2 / F13 / LV-77] 승인 후 Drawer·잔액 갱신

### 1. QA 문제와 원인

- 원본: `14-rerun-round2.md:243`, `bugs-by-service.md:56`.
- 다른 창에서 승인하고 75초가 지나도 Drawer·잔액은 결재 중. 새로고침하면 서버 정산 결과는 정상.
- 기존 목록은 SUBMITTING만 폴링, 상세와 연결 문서에는 폴링이 없었다. Leave의 결재 대기 상태명은 SUBMITTED다.

### 2. 수정 방향·구현

- `api/leave.ts`: SUBMITTED/SUBMITTING을 공통 대기 상태로 판단. 목록과 열린 상세를 5초 간격으로 갱신.
- 기존 `usePollWindow`의 2분 한도 재사용. 숨겨진 탭은 요청하지 않고 최종 상태에서는 중단한다. 최종 상태 확인을 위한 무한 폴링을 추가하지 않았다.
- 목록 상태가 바뀌면 해당 상세와 summary/ledger/grants 캐시를 무효화. 상세 상태 변경도 목록과 잔액 관련 캐시에 반영한다. 동일 상태 응답으로 무효화가 반복되지 않는다.
- `api/approval.ts#useDocument`: 두 번째 인자를 true로 준 휴가 화면에서만 진행 중 문서를 폴링한다. 다른 화면의 기본 동작은 false로 유지.
- `Requests.tsx`: 문서 폴링 연결, 수동 [새로 확인] 제공. 2분 뒤에는 자동 요청이 끝나며 수동 확인·재방문·창 포커스의 기존 조회를 사용할 수 있다.
- `AsyncSync`: `autoCheck=false` 옵션으로 휴가 Query와 중복된 5초 확인 타이머를 만들지 않는다. 다른 호출부의 기본값 true는 유지한다. 반영 중/반영 지연 표시는 유지한다.

### 3. 검증과 영향

- 실제 React Hook·TanStack Query 타이머로 SUBMITTED → APPROVED 목록/상세 갱신 및 최종 상태에서 추가 요청 중단 PASS.
- APPROVED/REJECTED/WITHDRAWN 상세 전환 시 목록·요약·원장·발생 캐시 갱신 PASS.
- 목록 변경의 상세 무효화, 숨김 탭·2분 한도, 문서의 INPROGRESS 폴링과 최종 상태 종료, 다른 문서 화면 기본 폴링 비활성 PASS.
- 서버 상태 전이와 회계 계산은 변경하지 않았다. 실제 두 브라우저와 Kafka 지연 표시의 EC2 재현은 후속 확인 대상이다.

최종 상태: **FIXED_VERIFIED** (Hook·상태 흐름 테스트 범위).

## [LV-B3 / F9 / LV-36·40] 이전 Preview·잘못된 예상 합계

### 1. QA 문제와 원인

- 원본: `14-rerun-round2.md:74~76`, `bugs-by-service.md:57`.
- 전체 날짜 해제 후 이전 합계 잔존: keepPreviousData와 입력 유무를 확인하지 않는 표시 조건이 결합됐다.
- 주말 합계 포함: 서버 Preview는 선택한 요청량을 반환하면서 별도 불가 사유를 제공한다. 프론트가 신청 불가 결과도 확정적인 차감·신청 후 잔여처럼 표시했다.

### 2. 수정 이유·실제 구현

- `useLeavePreview`의 placeholderData를 명시적으로 undefined로 설정해 전역 keepPreviousData 기본값을 상속하지 않게 했다.
- `Apply`: useMemo로 안정화된 현재 입력과 debounced 입력이 동일하고, 날짜가 있으며, 요청 완료·편집 가능 상태일 때만 결과를 표시한다.
- 입력 변경 즉시 이전 결과를 숨기고 debounce가 완료된 현재 키만 조회한다. 늦은 응답은 이전 Query 키에만 저장된다.
- `requestable=true`일 때만 차감 합계와 신청 후 잔여를 보여준다. 주말·공휴일·잔액 부족의 날짜별 사유는 유지한다.
- Backend Preview DTO·요청량 계산·FEFO·원장은 변경하지 않았다. 불가 날짜를 임의 제외하여 신청 가능량으로 재계산하지도 않는다.

### 3. 검증과 제한

- 실제 컴포넌트의 날짜 선택·전체 해제 시 합계 제거 PASS.
- 느린 이전 응답이 새 입력 결과를 덮어쓰지 않음 PASS.
- 주말·공휴일·잔액 부족 응답의 예상 잔여 숨김·신청 버튼 비활성 PASS.
- 기존 Backend 전체 회귀에서 실제 POST의 불가 날짜 거부와 쓰기 원자성 유지.
- 휴가 유형을 바꿀 때도 새 입력 키·참조 비교를 사용한다. 실제 브라우저 상호작용은 후속 체크리스트에 포함한다.

최종 상태: **FIXED_VERIFIED**.

## [LV-C2] 안내·수량·대량 날짜·처리 중 경고

원본: `14-rerun-round2.md:78,91,394,417~420`, `00-summary.md:251~252,298`, `bugs-by-service.md:59`. C38은 기존 C27/C28 증상의 후속 요약이며 별도 신규 버그로 중복 집계하지 않는다.

### C14 / LV-42 — 잔액 부족 안내

- 원인: 배분기에서 부족이 발생한 날짜로 INSUFFICIENT_BALANCE를 주고 프론트가 날짜 있는 사유는 행에만 표시.
- 수정: 코드를 기준으로 상단 Alert에도 한 번 표시. 날짜별 문구는 유지하고 동일 코드의 상단 중복만 제거.
- 이유: 미래 발생분 유효기간에 따른 날짜별 부족 정보를 삭제하거나 전체 잔액 부족으로 바꾸면 안 된다.
- 검증: 부족 응답의 상단 안내 및 제출 차단 PASS. FEFO와 Backend 응답 계약은 그대로다.
- 상태: **FIXED_VERIFIED**.

### C15 / LV-94·97, C28·C38 — 일수 반올림

- 원인: 모든 수량을 480으로 나누고 소수 첫째 자리로만 표시. 반올림한 행 합과 분 합계의 반올림 결과가 달랐다.
- 수정: `formatLeaveAmount()`를 추가해 정확한 시간·분을 먼저 표시하고, 일수는 괄호 안 보조 표시로 제공한다. 정확히 일치하지 않는 일수에는 '약'을 붙인다.
- 예: `-30분 (약 -0.1일)`, `1시간 (약 0.1일)`, `4시간 (0.5일)`, `91시간 (약 11.4일)`.
- 홈·잔액·신청·상세·원장·정책 이월 한도에서 같은 함수를 사용한다. 기존 formatLeaveDays의 계약은 유지한다.
- 검증: 0/±30/60/240/480/5,460분 표시 7개 PASS, 타입 검사·빌드 PASS.
- 기존 원장 delta·분 수량·합산·DB는 변경하지 않았다. 표시한 일수를 합산하여 장부 수량을 만들지 않는다.
- 상태: **FIXED_VERIFIED**.

### C28 / LV-45 — -0.0일

- 원인: 신청 행에서 수량이 0이어도 음수 문자열을 앞에 붙임.
- 수정: 0은 '차감 없음', 실제 차감은 음수 분 수량으로 포맷. 공통 수량 0은 '0분'.
- 검증: NONE 유형의 차감 없음 표시 및 -0.0일 부재 PASS.
- 상태: **FIXED_VERIFIED**.

### C27·C38 / LV-57 — 대량 날짜

- 원인 근거: 입력 객체가 렌더링마다 재생성되어 기존 debounce Effect가 반복 예약될 수 있었다. 매 렌더링 JSON.stringify 비교와 Map 재생성도 있었다. 이미 있던 300ms debounce만 추가한 것이 아니다.
- 수정: 입력과 응답 파생 Map을 useMemo로 안정화하고 참조 비교로 현재 입력 일치를 확인한다. 입력이 바뀌지 않으면 debounce를 재예약하지 않는다. 최대 366개 안내를 표시한다.
- 테스트: 입력이 그대로인 700ms 대기 및 사유만 변경한 뒤 Preview 추가 호출 없음 PASS. 날짜를 월별로 선택해 366개 도달 안내·367번째 추가 거부 PASS.
- 측정 한계: React Test Renderer + UI 대역의 366개 테스트 실행 시간은 실제 브라우저/Ant Design 비용이 아니다. 수정 전후 브라우저 성능 수치는 측정하지 않았고, 수십 초 멈춤이 해소됐다고 확정하지 않는다.
- 실제 후속 측정: 100/300/366개, 연속 클릭, 입력 지연·긴 태스크·렌더 횟수·Network 호출 수·선택 해제·달 이동. 측정 결과에 따라 행 memo/표시 범위 최적화를 별도 검토.
- 상태: **FIXED_UNVERIFIED** (코드 수준 개선 및 요청/한도 검증 완료, 브라우저 성능 미검증).

### LV-53 — SUBMITTING과 중복 날짜 경고

- 원인: 제출 후 휴가 Query 무효화로 Preview가 자신의 SUBMITTING 신청을 기존 신청으로 감지.
- 수정: 제출 진행·SUBMITTING에서는 편집용 Preview를 비활성화하고 결과를 숨긴다. 입력 잠금·원래 키 유지·기존 진행 상태 안내를 우선한다.
- 검증: SUBMITTING 후 Preview 추가 요청 없음, 입력 잠금, 재확인 POST의 동일 Body·Idempotency-Key 유지 PASS.
- 실제 다른 신청의 중복 날짜 검증과 Backend 충돌 규칙은 변경하지 않았다.
- 상태: **FIXED_VERIFIED**.

LV-C2 그룹 최종 상태: **FIXED_UNVERIFIED**. 성능 미검증을 나머지 표시 기능의 검증과 구분한다.

## [LV-06] 정책 미배정 직원의 빈 상태 버튼

### 문제·원인

- 원본: `01-02-04-05-07-08-shell-home-leave-approval-automation-org.md:74`, `14-rerun-round2.md:322`, `04-leave.md`의 LV-06 결과.
- 헤더는 noPolicy를 확인하지만 요청 목록의 EmptyState는 무조건 신청 버튼·달력 안내를 표시했다.

### 수정 이유·구현

- `LeavePage`: 기존 noPolicy와 신청 가능 유형 Query로 canApply를 계산하고 헤더·빈 목록에 같은 결과를 전달.
- `Requests`: 정책 미배정, 정책 있음/유형 없음, 신청 내역 없음, 유형 조회 중/오류를 구분.
- 정책 없음은 담당자 문의 문구, 유형 없음은 유형 없음 문구, 조회 오류는 ErrorPanel·재시도, 조회 중은 확인 중 문구.
- 새 Backend 권한 체계나 정책 판단 API를 만들지 않았다. 존재하는 신청 이력은 정책 유무와 관계없이 계속 조회한다.

### 검증·영향

- 정책 없음·유형 없음에서 action 없음, 신청 가능 시 action 있음 3개 컴포넌트 테스트 PASS.
- 타입 검사·빌드 PASS. 실제 Gateway 오류·로딩 화면과 다양한 화면 폭은 추가 수동 확인 대상이다.

최종 상태: **FIXED_VERIFIED** (위 빈 상태 조합 범위).

## [LV-C1 / C13 / E3-07 추가] 반차 충돌 — POLICY_PENDING

- 원본: `14-rerun-round2.md:109`, `bugs-by-service.md:58`.
- 현재 `LeaveRequestRepository.findBlockingDates()`는 SUBMITTING/SUBMITTED/APPROVED의 날짜만 조회한다.
- `LeaveRequestValidationService`는 같은 날짜면 portion과 관계없이 EXISTING_REQUEST. 같은 신청 안의 동일 날짜 두 행도 DUPLICATE_DATE로 거부한다.
- 기존 PostgreSQL 테스트는 APPROVED AM_HALF → PM_HALF 충돌을 의도한 정상 계약으로 검증한다. 이번에도 그대로 통과했다.
- Preview와 T1이 같은 ValidationService를 사용하므로 현재 규칙은 일관된다. 관련 Repository·Validation·충돌 테스트는 이번에 변경하지 않았다.

### 정책 결정 후 필요한 변경

1. FULL/AM_HALF/PM_HALF 충돌표와 서로 다른 유형 혼합 허용 여부.
2. 별도 신청의 AM/PM만 허용할지, 동일 신청의 두 행도 허용할지.
3. portion 포함 조회와 Preview/T1 공통 검증, 직원 잠금 후 재검증 유지.
4. 각각 240분 예약·사용하고 한쪽 취소 시 해당 allocation의 CONSUME만 RESTORE하는지 확인.

### Attendance 읽기 확인

`EmployeeLeaveRepository.findByEmpIdAndLeaveDate()`는 List를 반환하며 '오전·오후 반차를 따로 신청하면 두 건' 주석이 있다. `LeaveServiceImpl.applyApprovedLeave()`는 신청 ID로 중복을 확인하고 날짜별 저장한다. `cancelLeave()`는 해당 신청 ID의 행만 삭제한 뒤 날짜를 재계산한다.

따라서 다중 반차를 담을 기반은 있으나, 이것만으로 모든 근무 계산·유급/무급·한쪽 취소 재계산이 정확하다고 보장하지 않는다. AttendanceDailyProcessor의 실제 조합 처리와 교차 서비스 통합 테스트가 필요하다. 다른 서비스는 수정하지 않았다.

최종 상태: **POLICY_PENDING**. 이번 작업의 수정 완료 수에 포함하지 않는다.

## QA 전체 조치 현황

| QA ID | 문제 | 영역/주요 파일 | 처리 상태 | 검증 결과 |
|---|---|---|---|---|
| LV-B1 / F18 / LV-54 | 미생성 UNKNOWN 복구 | BE Orchestration/ResultService/ApprovalHttpClient | FIXED_VERIFIED | 단위·HTTP·PG·Kafka PASS, EC2 미실행 |
| LV-B2 / F13 / LV-77 | 승인 상태 미갱신 | FE leave.ts/Requests/approval.ts/AsyncSync | FIXED_VERIFIED | 실제 Query 타이머·상태·캐시 PASS |
| LV-B3 / F9 / LV-36·40 | Preview 잔존·불가 합계 | FE Apply/leave.ts | FIXED_VERIFIED | 늦은 응답·해제·불가 상태 PASS |
| C14 / LV-42 | 부족 안내 | FE Apply | FIXED_VERIFIED | 상단 안내·제출 차단 PASS |
| C15 / LV-94·97 / C28·C38 | 분 정밀도 표시 | FE format/휴가 화면 | FIXED_VERIFIED | 수량 7개·타입·빌드 PASS |
| C28 / LV-45 | 음수 0 | FE Apply/format | FIXED_VERIFIED | 차감 없음·음수 0 부재 PASS |
| C27·C38 / LV-57 | 대량 날짜·한도 | FE Apply | FIXED_UNVERIFIED | 반복 요청·366 제한 PASS, 브라우저 성능 미측정 |
| LV-53 | 처리 중 중복 경고 | FE Apply/leave.ts | FIXED_VERIFIED | Preview 중단·같은 키/Body PASS |
| LV-06 | 정책 없음 신청 버튼 | FE LeavePage/Requests | FIXED_VERIFIED | 빈 상태 3조합 PASS |
| LV-C1 / C13 | 반차 충돌 | 기존 규칙 유지 | POLICY_PENDING | 기존 날짜 충돌 회귀 PASS |

## 변경 파일 목록

경로는 저장소 루트 기준이다. 새 QA 원본 수정이나 다른 서비스 변경은 없다.

### Backend — 12개

공통 소스 경로: `Backend/leave-service/src/main/java/com/ssafy/leaveservice/`

| 경로 | 핵심 변경·목적 | QA | 관련 테스트 |
|---|---|---|---|
| client/approval/ApprovalClient.java | supportsIdempotentResume/resume 경계 | LV-B1 | OrchestrationServiceTest |
| client/approval/ApprovalCreationResult.java | 내부 LOOKUP_NOT_FOUND | LV-B1 | ApprovalHttpClientTest |
| client/approval/ApprovalHttpClient.java | 동일 UUID 재개 + 원격 신원 확인 | LV-B1 | ApprovalHttpClientTest/ApprovalLeaveFlowIntegrationTest |
| domain/request/dto/LeaveApprovalCallStartResult.java | 시도 번호 전달, 기존 생성자 유지 | LV-B1 | ResultServiceTest |
| domain/request/entity/LeaveRequest.java | resumeApprovalCall, 공통 시도 증가 | LV-B1 | ResultServiceTest/PG |
| domain/request/service/LeaveApprovalResultService.java | 재개 호출권·오래된 실패 차단 | LV-B1 | ResultServiceTest/PG |
| domain/request/service/LeaveSubmissionOrchestrationService.java | 조회→재개→T2 분리 | LV-B1 | OrchestrationServiceTest/Flow |

테스트 공통 경로: `Backend/leave-service/src/test/java/com/ssafy/leaveservice/`

| 경로 | 검증 내용 |
|---|---|
| client/approval/ApprovalHttpClientTest.java | 404 구분·재개 Body·기안자·HTTP 실패 |
| domain/request/LeaveApprovalResultServiceTest.java | 재개 단일 획득·30초 경계·실패 시도 번호 |
| domain/request/LeaveSubmissionOrchestrationServiceTest.java | 재개 조건·저장 Body·권한 실패·경합 |
| domain/request/LeaveApprovalResultPostgresIntegrationTest.java | 실제 동시 호출권 및 원장 보존 |
| domain/request/messaging/ApprovalLeaveFlowIntegrationTest.java | 미생성 503·복구·문서 1개·Kafka 정산·DLT 재전달 |

### Frontend — 15개

| 실제 경로 | 목적·핵심 변경 | QA/검증 |
|---|---|---|
| Frontend/src/api/leave.ts | isLeavePending, 목록/상세 폴링·캐시, Preview 수명 | B2/B3/C2, Node Hook 테스트 |
| Frontend/src/api/approval.ts | useDocument의 휴가 전용 선택적 폴링 | B2, 기본 false 보존 테스트 |
| Frontend/src/ui/AsyncSync.tsx | autoCheck=false 시 중복 확인 타이머 제거 | B2, 기존 기본 true 유지 |
| Frontend/src/leave/Apply.tsx | 입력 useMemo·현재 결과·합계 조건·안내·잠금 | B3/C2, 컴포넌트 이벤트 테스트 |
| Frontend/src/format/index.ts | formatLeaveAmount 정확한 시간·분 | C15/C28/C38, 수량 7개 |
| Frontend/src/leave/Balances.tsx | 홈/잔액의 정확한 시간·분 표시 | C15, 타입/빌드 |
| Frontend/src/leave/Ledger.tsx | 지급·원장 delta의 정확한 시간·분 표시 | C15, 포맷 테스트·타입/빌드 |
| Frontend/src/leave/PolicyCard.tsx | 이월 한도 표시 일관성 | C15, 타입/빌드 |
| Frontend/src/leave/Requests.tsx | 목록·상세 수량, 문서 폴링, 빈 상태·수동 확인 | B2/C15/LV-06 |
| Frontend/src/pages/leave/LeavePage.tsx | 동일 canApply 판단으로 헤더·빈 상태 연결 | LV-06 |
| Frontend/package.json | test:leave 명령, React 18 테스트 렌더러 개발 의존성 | 검증 전용 |
| Frontend/package-lock.json | 테스트 렌더러 버전 고정 | 앱 런타임 의존성 변경 없음 |
| Frontend/scripts/leave-qa-loader.mjs | Node에서 TS/별칭 로드, 테스트용 브라우저 최소 환경 | 검증 전용 |
| Frontend/scripts/leave-qa-ui.mjs | DOM/포털 UI 테스트 대역 | 실제 CSS/Ant Design 성능 미검증 명시 |
| Frontend/scripts/leave-qa.test.mjs | 실제 React 상태·Query·Axios 대역 회귀 26개 | B2/B3/C2/LV-06 |

테스트 프레임워크 전체를 추가하지 않았다. Node 기본 test 러너와 React 18.3.1과 같은 버전의 react-test-renderer만 개발 의존성으로 추가했다. 실제 API 요청은 Axios adapter 대역이 처리하며 공용 서버로 전송하지 않는다. 검증 Node 버전은 24.18.0이다.

## 실행한 테스트 및 결과

| 검증 | 명령/환경 | 결과 |
|---|---|---|
| Backend 전체 회귀 + bootJar | leave-service에서 `./gradlew.bat test bootJar --console=plain`, Java 21 | PASS, 5분 32초, 2,151개·105클래스, 실패/오류/스킵 0 |
| PostgreSQL 포함 테스트 | 위 전체 실행의 부분집합, Testcontainers postgres:17-alpine 실제 17.11 | PASS 1,445개 (Kafka 함께 사용하는 9개 포함) |
| PostgreSQL/Kafka 없는 테스트 | 전체 실행의 나머지 단위/Mock HTTP/MVC 등 | PASS 706개 |
| 집중 단위/Mock HTTP | OrchestrationServiceTest 28 + ResultServiceTest 17 + ApprovalHttpClientTest 17 | PASS 62개, 전체 결과에 포함 |
| 집중 PG/HTTP/Kafka | ResultPostgresIntegrationTest 89 + ApprovalLeaveFlowIntegrationTest 8 | PASS 97개, 전체 결과에 포함 |
| Application context | LeaveServiceApplicationTests | PASS 1개, 격리 PG 사용, 제외하지 않음 |
| Flyway·Hibernate | 전체 테스트 Context, 기존 V1→V8, ddl-auto=validate | PASS, 기존 마이그레이션 수정 없음 |
| Frontend 휴가 회귀 | Frontend에서 `npm run test:leave` | PASS 26, 실패/취소/스킵 0 |
| TypeScript | `npm run typecheck`, 빌드 시 `tsc -b` 포함 | PASS |
| Frontend production build | `npm run build` | PASS |
| ESLint | 기존 ESLint 의존성·설정·명령 없음 | NOT_RUN. 이번 작업용 전체 린트 체계는 추가하지 않음 |
| 실제 브라우저 성능/레이아웃 | Ant Design·다중 브라우저·실제 네트워크 | NOT_RUN, 후속 확인 |
| 실제 EC2/다른 MSA 프로세스 | 공용 쓰기·서비스 중지 금지 | NOT_RUN |
| 변경 검증 | `git diff --check` 및 최종 경로 검사 | PASS |

테스트 개수는 Gradle XML의 test case 실행 수다. 파라미터화 실행과 동일 파일 내 프로필 테스트 클래스도 각각 집계한다. 집중 실행 수를 전체 수에 다시 더하지 않는다. PostgreSQL 1,445개는 해당 컨테이너를 사용하는 클래스 기준이며 순수 Repository 테스트만 뜻하지 않는다.

환경 및 중간 검증 이력:

- 초기 Docker가 꺼져 있었으며 사용자 기동 후 격리 테스트를 실행했다. 실제 개발 DB는 사용하지 않았다.
- 로컬 JAVA_HOME/PATH를 Java 21 설치 경로로 지정했다. 보안 라이브러리 1.1.0 대체나 설정 파일 수정은 없다.
- npm 의존성 설치는 샌드박스 DNS 오류 후 허용된 네트워크에서 lockfile 기준으로 진행했다.
- 404 내부 결과 분류를 바꾼 첫 단위 실행에서 이전 UNKNOWN 기대값 1개가 실패했다. 새 의미를 검증하도록 갱신한 뒤 전체 회귀 통과했다. 테스트 삭제·비활성화는 없다.
- 프론트 테스트 로더의 Node 환경/TS 변환, UI 대역의 action 렌더링, GET/POST 대역 분기를 정리한 뒤 최종 26개가 통과했다.
- React Router v7 전환 안내와 기존 Vite 500KB 초과 번들 경고가 있다. 빌드는 성공했으며 이번 QA 범위 밖의 번들 분할은 하지 않았다.

## 미해결·후속 검증

1. **LV-C1 POLICY_PENDING**: 반차 충돌표·동일 신청/별도 신청 허용·Attendance 조합 계산·한쪽 관리자 취소 정책 결정.
2. **LV-C2 성능**: 실제 브라우저 100/300/366개 선택의 전후 수치와 긴 태스크 측정. 현재는 입력 안정화·반복 호출 방지·한도 동작까지 검증했다.
3. **LV-B1 배포 계약**: 운영 Approval에도 UUID Advisory Lock·UNIQUE·동일 신청 검증이 적용됐는지 확인. 기안자 토큰 전달·권한 은닉 404·재개 POST 충돌을 EC2에서 계정별 확인.
4. **DLT 수동 복구**: 문서 연결을 완료한 뒤 원본 최종 이벤트 재전달과 중복 정산 방지를 운영 절차로 관리. 문서 연결 성공을 최종 회계 정산 완료로 표시하지 않는다.
5. **다중 창 확인**: 승인/반려/회수, Kafka 지연, 2분 자동 조회 종료·수동 확인·재방문, 홈 잔액 동기화.
6. **실제 UI**: 정확한 시간·분 병기로 길어진 문구의 홈/표/모바일 폭 확인. API 수량이나 회계 계약을 되돌려 표시를 맞추지 않는다.
7. **QA 당시 재확인 GET 기록**: 이미 POST인 소스와 다른 관찰의 원인은 당시 배포 번들·Network 기록이 있어야 확정할 수 있다.

다음 실제 E2E는 전용 계정·고유 멱등 키·고립된 재현 환경에서 수행한다. 공용 EC2의 승인/지급 데이터를 초기화하거나 장애를 주입하지 않는다.
