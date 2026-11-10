# 휴가 관리자 화면 구현 및 검증

검증일: 2026-11-10. 화면: `/admin/leave`.

이번 변경은 Frontend 내부에만 적용했다. Backend, Flyway, 다른 서비스, 공용 EC2와 DB는 변경하지 않았으며 배포·commit·push도 수행하지 않았다.

## 접근 권한

- 기존 시스템 관리자 `RequireAdmin`과 별개로 `RequireLeaveAdministrator`를 사용한다.
- 현재 인증 세션과 `GET /api/v1/employees/me`의 최신 직원 ID·재직 상태·직책명 `휴가관리자`를 확인한다. Role이나 직책 ID 상수, 이메일로 허용하지 않는다.
- 전용 권한 확인 API가 없으므로 기존 `GET /api/v1/leaves/admin/types`로 서버의 JWT·최신 HR 권한 검증도 확인한다. 결과 유형 목록은 기존 캐시에 재사용한다.
- USER/ADMIN 모두 직책이 맞아야 메뉴와 라우트 접근을 허용한다. 권한 조회 중에는 관리자 내용을 표시하지 않으며 403·503 등 확인 실패 시 차단한다.
- 직원 ID·직책 ID·재직 상태 변경은 별도 권한 Query Key를 사용한다. 재진입·창 포커스 및 보이는 창의 30초 주기로 재확인한다. 기존 로그아웃의 QueryClient 초기화도 유지한다.
- 다른 관리자 화면과 메뉴는 기존 Role 정책을 유지한다. 최종 데이터 접근 권한은 각 Backend API가 검증한다.

## 화면과 실제 Endpoint

기존 Layout, 정책/유형/사원 탭, Card, 상태 표시, Ant Design Table을 유지하고 관리 버튼·Form·확인 Modal 및 신청 관리 탭을 추가했다.

아래 경로는 모두 `/api/v1/leaves/admin` 기준이다. 실제 Controller와 DTO를 확인한 27개 관리자 Endpoint를 연결한다.

| 업무 | Method / 경로 | 화면 동작 |
|---|---|---|
| 정책 목록·상세 | GET `/policies`, GET `/policies/{policyId}` | 그룹 선택 및 이름 편집 전 기본 정보 확인 |
| 정책 생성·이름 변경 | POST `/policies`, PATCH `/policies/{policyId}` | 이름 원문 보존, 최대 100자, 중복 이름 허용 |
| 버전 목록·상세 | GET `/policies/{policyId}/versions`, GET `/policies/{policyId}/versions/{versionId}` | 활성 포인터 결과와 설정 표시 |
| 버전 생성·수정·삭제 | POST `/policies/{policyId}/versions`, PATCH/DELETE `/policies/{policyId}/versions/{versionId}` | 활성 버전 버튼 차단, 사용 이력은 서버 최종 판단 |
| 버전 활성화 | PUT `/policies/{policyId}/active-version` | `{ versionId }`, 영향 확인 후 실행 |
| 발생 규칙 | GET/PUT `/policies/{policyId}/versions/{versionId}/accrual-rules` | `{ rules }` 전체 교체, 빈 목록 삭제 확인 안내 |
| 유형별 신청 규칙 | GET `/policies/{policyId}/versions/{versionId}/type-rules`, PUT 같은 경로의 `/{leaveTypeId}` | 마스터 단위 호환성·시간 상한 검사 |
| 휴가 유형 | GET/POST `/types`, PATCH `/types/{leaveTypeId}` | 생성·수정, 비활성화는 `isActive=false` PATCH |
| 정책 배정 | GET/POST `/employees/{employeeId}/policy-assignments` | 현재/과거/미래 이력, 그룹 ID와 적용 기간 배정 |
| 직원 휴가 | GET `/employees/{employeeId}/balances`, `/grants`, `/ledger` | 기존 조회 및 정확한 분 수량 표시 |
| 수동 지급 | POST `/employees/{employeeId}/grants` | INITIAL/ADDITIONAL, 기간·사유·분 수량 및 Idempotency-Key |
| 잔액 증감 | POST `/employees/{employeeId}/adjustments` | 기존 accountId/grantId, 부호 있는 quantityDelta·사유·키 |
| 신청 관리 | GET `/requests`, GET `/requests/{requestId}` | 전체 목록 명시적 조회, 상세 Drawer, 결재 문서 연결 |
| 승인 휴가 취소 | POST `/requests/{requestId}/cancel` | APPROVED만, 사유 확인, Attendance 원복 결과 재확인 |

버전 `effectiveFrom=null`을 지원한다. CUSTOM 발생 주기는 실제 근속 구간·분 수량 설정만 편집하며 존재하지 않는 자동 지급 일정은 생성하지 않는다. 버전 번호·PK는 서버 응답을 사용한다.

휴가 유형은 기본 편집에서 이름·활성 상태만 변경한다. 업무 설정은 별도 체크 후 편집하며 사용 중인 유형의 변경 제한을 서버 409로 유지한다. 유형별 신청 규칙에서 DAY→FULL, HALF_DAY→HALF, HOUR→HOUR, MIXED→전체 허용 단위를 검사하고 마스터 maxMinutes를 확장하지 않는다.

정책 배정은 버전 고정 배정이나 자동 지급이 아니다. 활성 버전을 가진 그룹을 확인하고 기존 배정 종료·기간 충돌은 서버 계약을 따른다. 현재 배정 표시는 조회한 이력과 브라우저 시각 기준이며 지급 때 서버가 다시 검증한다.

## 캐시 및 오류 처리

- `useLeaveAdminMutation`은 작업별 관련 Query Key만 갱신한다. 같은 Hook의 처리 중 재호출은 하나의 HTTP Promise를 공유한다.
- 정책 변경은 그룹·버전·규칙 및 본인의 적용 정책/신청 가능 유형을 갱신한다. 이름 변경은 배정 이력의 현재 정책명도 갱신한다.
- 지급·조정은 해당 직원의 잔액·발생분·원장을 갱신한다. 대상이 본인이면 기존 사용자 요약·발생분·원장 캐시도 갱신한다.
- 취소 결과는 해당 직원의 조회와 신청 목록·상세, 본인 요약·원장·신청 캐시에 반영한다. 결과 확정 전 낙관적 CANCELLED 표시는 하지 않는다.
- 직원·그룹·버전 전환 시 이전 대상 데이터를 placeholder로 표시하지 않는다.
- 401/403/409/Validation/503 오류는 기존 ApiError와 ErrorPanel로 코드·메시지를 표시한다. 쓰기 Mutation 자동 재시도는 없다.
- 일반 생성·변경의 응답이 불명확하면 해당 Modal의 저장을 차단하고 재조회로 실제 반영 여부를 확인하도록 안내한다. 이 API들에는 지급과 같은 멱등성 계약이 없으므로 새 작업을 다시 시작하기 전 확인이 필요하다.
- Form 필수값, 정수 수량, 기간, 사유를 검증한다. 이름을 임의로 trim하거나 분 수량을 반올림해서 전송하지 않는다.

## 지급·조정의 중복 방지

1. 로그인 직원 ID·작업·대상 직원 ID별로 원문 Body와 UUID를 HTTP 호출 전에 sessionStorage에 저장한다. 저장 실패나 읽을 수 없는 기존 요청은 쓰기부터 차단한다.
2. 동시 클릭을 차단하고 요청 중/불명 상태에는 입력을 잠근다.
3. Timeout·5xx·응답 불일치 후에도 같은 키와 원문을 유지한다. 화면 재마운트 후에도 같은 요청을 재확인한다.
4. 응답의 실제 식별자·대상·수량을 확인한 뒤 관련 조회를 갱신하고 보관 요청을 제거한다.
5. 명확한 4xx 입력·권한 거절은 새 입력을 허용하되 멱등성 충돌은 기존 요청을 유지한다.

JWT·쿠키·비밀번호를 이 저장소에 기록하지 않는다. 다만 업무 요청 본문과 사유는 탭에 보관된다. 탭 종료·저장소 삭제·다른 브라우저에서는 원문과 키가 복구되지 않으므로, 결과 불명 상태에서 새 지급/조정을 만들기 전에 기존 원장과 서버 처리를 확인해야 한다. 여러 탭에서 서로 다른 UUID로 시작한 독립 지급까지 막는 계약은 아니다.

수동 지급은 새 발생분을 생성한다. 조정은 현재 유효한 기존 발생분만 선택하며 미래·만료 발생분은 제외한다. 표시하는 예상 조정 후 가용량은 계정 기준 참고값이고, 발생분별 예약·정상 지급 이력·최신 잔액 검증은 서버가 최종 수행한다.

## 관리자 취소

- Leave 취소 API만 호출한다. Approval 문서의 상태를 변경하거나 철회 API를 호출하지 않는다.
- 관리자와 최초 사유는 서버에 보존되며 UUID도 서버가 생성한다. 프론트에서 별도 원격 취소 UUID를 만들지 않는다.
- IN_PROGRESS/UNKNOWN이면 신청 APPROVED를 유지하고 실제 처리 상태·UUID를 표시한다. 저장한 동일 사유의 POST로 결과를 재확인한다.
- COMPLETED/CANCELLED 확인 후에만 완료 메시지를 표시한다. FAILED 및 LEAVE_021은 새 원복 작업으로 자동 재시도하지 않는다.
- LEAVE_025/503은 근태 원복 연동 비활성화 안내로 표시하며 성공으로 취급하지 않는다.

## 현재 API의 제한과 후속 계약

- 관리자 신청 목록은 전체 배열만 제공한다. 서버 검색·필터·페이징 API가 없어 직원/상태/기간 필터를 가장하지 않았다. 사용자가 전체 조회 버튼을 눌러 불러오며 표의 20개 단위 페이지는 수신 결과의 화면 분할이다. 대량 운영에는 서버 검색·페이징이 필요하다.
- 잔액 유형 독립 전체 목록 API가 없다. 기존 휴가 유형에 연결된 잔액 유형을 선택하거나 실제 ID를 입력하며 서버가 존재/활성 상태를 확인한다. 미연결 잔액 유형을 모두 탐색하는 UI는 제공하지 않는다.
- 버전 응답에 수정·삭제 가능 여부나 사용 이력 플래그가 없다. 활성 버전은 미리 차단하고 과거 사용 이력 제한은 서버 409로 안내한다.
- 신청 상세 GET은 진행 중 취소 UUID·최초 관리자·사유를 제공하지 않는다. 현재 탭의 요청은 저장된 사유로 복구하지만 다른 브라우저에서 진행한 취소의 완전한 조회/복구 화면에는 추가 조회 정보가 필요하다.
- 원장 GET에 작업 관리자 ID가 없어 완전한 관리자 감사 로그 화면은 만들지 않았다. 기존 지급·조정·복원 이벤트와 사유 및 취소 확정 감사 필드를 표시한다.
- 분 단위 수량을 그대로 전송한다. 기존 공통 포맷의 1일=480분 환산은 보조 표시에만 사용하며 일수 반올림 값을 계산이나 요청에 사용하지 않는다.

## 변경 파일

신규:

- `src/auth/RequireLeaveAdministrator.tsx`
- `src/leave/admin/AdminForms.tsx`
- `src/leave/admin/PolicyActions.tsx`
- `src/leave/admin/TypeActions.tsx`
- `src/leave/admin/EmployeeActions.tsx`
- `src/leave/admin/RequestManagement.tsx`
- `scripts/leave-admin-loader.mjs`
- `scripts/leave-admin.test.mjs`
- `docs/LEAVE_ADMIN.md`

수정:

- `src/App.tsx`
- `src/shell/nav.ts`
- `src/shell/AppShell.tsx`
- `src/pages/admin/LeavePolicyPage.tsx`
- `src/api/leaveAdmin.ts`
- `scripts/leave-qa-loader.mjs`
- `scripts/leave-qa-ui.mjs`
- `package.json`

## 검증 결과와 범위

Frontend에서 실행:

```sh
npm run test:leave:admin
npm run test:leave
npm run typecheck
npm run build
git diff --check
```

- 신규 관리자 테스트 58개 통과. 권한 조합/차단, 기존 시스템 관리자 Guard·메뉴 유지, 14개 쓰기 API 경로·Body·키, 캐시 갱신, 입력 전달, 중복 호출, 지급·조정 Timeout 재마운트, 취소 UNKNOWN/503/전송 오류를 Mock으로 검증한다.
- 기존 사용자 휴가 테스트 26개 통과. 합계 84개, 실패·취소·스킵 0개.
- TypeScript 검사 및 Vite production 빌드 통과. 빌드의 500KB 초과 청크 경고는 남아 있다.
- ESLint 설정·의존성·실행 Task가 현재 프로젝트에 없어 ESLint는 실행하지 않았다.
- React 상태·실제 TanStack Query·기존 Axios Client를 검증하되, Ant Design은 기존 테스트 패턴의 UI 대역이다. 실제 브라우저의 Form Validation 엔진·Modal 포털·레이아웃까지 검증한 E2E 결과는 아니다.
- PostgreSQL/Backend 회귀는 이번 Frontend 변경에서 실행하지 않았다. 실제 EC2 쓰기 요청도 보내지 않았다.

## 배포 후 화면 확인

1. USER+휴가관리자, ADMIN+일반 직책 계정으로 메뉴와 `/admin/leave` 직접 접근을 비교한다. 다른 관리자 메뉴는 기존 Role 정책을 확인한다.
2. 전용 테스트 정책을 생성하고 버전·규칙을 등록한 뒤 활성 버전 교체와 활성/사용 버전 수정 거부를 확인한다.
3. 전용 직원에게 정책을 배정하고 수동 지급 후 실제 발생분·원장·잔액을 확인한다. 조정은 기존 지급 발생분을 선택한다.
4. 전용 APPROVED 신청의 상세에서 관리자 취소를 확인한다. 완료 시 CANCELLED·RESTORE와 Attendance 원복을 확인하고 Approval 문서는 승인 상태를 유지하는지 확인한다.
5. Timeout/UNKNOWN은 동일 키·원문/사유로 확인하며 새 지급이나 취소 의도를 만들지 않는다. 실제 브라우저에서 폼 검증, 날짜 선택, Modal 닫기/재열기와 화면 크기도 확인한다.
