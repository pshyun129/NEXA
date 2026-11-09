# 결재 문서 생성 요청 가이드 (approval-service)

결재 문서를 만들 때 어떤 데이터를 보내야 하는지 정리한 문서입니다.
결재 화면(프런트엔드)과 결재가 필요한 서비스(leave-service, attendance-service, ai-service 업무 자동화)가 대상입니다.

## 요청

```
POST /api/v1/documents
Authorization: Bearer {access token}
Content-Type: application/json
```

- **기안자는 토큰의 사원**입니다. 요청 본문에 기안자를 넣지 않습니다.
- 다른 서비스가 대신 호출할 때도 **신청한 사원의 토큰**으로 호출해야 그 사원이 기안자가 됩니다.

## 요청 본문

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `title` | string | O | 제목 (최대 255자) |
| `formType` | string | O | 문서 양식. 아래 [양식별로 보내야 하는 값](#양식별로-보내야-하는-값) 참고 |
| `sourceType` | string | 조건부 | 문서가 만들어진 곳: `DIRECT` \| `LEAVE` \| `ATTENDANCE` \| `AUTOMATION`. 생략하면 `DIRECT` |
| `sourceId` | number | 조건부 | 원본 신청 ID. `DIRECT`가 아니면 필수, `DIRECT`면 보내지 않음 |
| `requestKey` | string (UUID) | 조건부 | 생성 요청 키. **요청하는 쪽이 만들어서** 보냄. `DIRECT`가 아니면 필수, `DIRECT`면 선택. 아래 [requestKey 만드는 법](#requestkey-만드는-법) 참고 |
| `attributes` | object | X | 전결 단계를 정하는 신청 값. 아래 [결재선 자동 결정](#결재선-자동-결정-위임전결-규정) 참고 |

> 문서번호(`docNo`)는 서버가 만듭니다. 요청에 넣어도 무시합니다.
>
> **결재선은 받지 않습니다.** 위임전결 규정과 조직도로 서버가 만듭니다([결재선 자동 결정](#결재선-자동-결정-위임전결-규정)). 예전 형식처럼 `approvalLines`를 보내면 오류 없이 **무시**됩니다.
> 참조자·열람자, 추가 결재자는 상신 후 [결재선 변경 API](#상신-후-결재선-변경)로 넣습니다.

### requestKey 만드는 법

`requestKey`는 approval이 "이 요청이 아까 그 요청의 재시도인지" 알아보는 값입니다.
두 요청이 같은 신청에서 나왔다는 건 **요청을 보낸 쪽만 알기 때문에** approval이 대신 만들 수 없습니다.
(응답으로 받는 방식도 안 됩니다. 응답을 못 받은 상황, 즉 타임아웃에서 쓰는 값이기 때문입니다.)

규칙

1. **신청 하나당 키 하나.** 요청을 시도할 때마다 새로 만들면 안 됩니다. 재시도할 때마다 새 UUID를 쓰면 중복을 막지 못합니다.
2. **결재 요청을 보내기 전에 DB에 저장.** 요청 도중 서비스가 재시작돼도 같은 키로 재시도하거나 조회할 수 있어야 합니다.
3. **재시도·조회에 같은 키 사용.** 결과를 모르면 같은 키로 다시 생성 요청하거나 [조회](#요청-키로-문서-조회)합니다.

```java
// 신청을 저장할 때 키를 함께 만든다 (결재 요청 전)
leaveRequest.approvalRequestId = UUID.randomUUID();
leaveRequestRepository.save(leaveRequest);

// 결재 요청. 재시도해도 같은 값을 쓴다
approvalClient.create(new CreateDocument(..., leaveRequest.getApprovalRequestId()));
```

| 서비스 | 할 일 |
|---|---|
| leave-service | 이미 신청마다 `approvalRequestId`(UUID)를 만들어 저장함. 그 값을 `requestKey`로 보내면 됨 |
| attendance-service | 근태 신청에 UUID 컬럼을 추가해 신청 저장 시 만들고, 결재 요청에 담아 보냄 |
| ai-service | 워크플로우 실행(결재 단계)마다 UUID를 만들어 저장하고 결재 요청에 담아 보냄 |
| 프런트 (직접 작성) | 선택. 작성 화면을 열 때 하나 만들어 두면 상신 버튼을 두 번 눌러도 문서가 하나만 생김 |

### 재시도와 requestKey

같은 `requestKey`로 다시 요청하면 문서를 새로 만들지 않습니다. 타임아웃처럼 결과를 모를 때 **같은 키로 다시 보내도 안전합니다.**

| 상황 | 응답 |
|---|---|
| 처음 요청 | `201`, 새 문서 |
| 같은 키, 같은 신청 (기안자·양식·출처·원본 ID가 같음) | `200`, 처음 만든 문서 (제목·신청 값이 달라도 처음 문서 그대로) |
| 같은 키, 다른 신청 | `409` `APPROVAL_021` |

## 결재선 자동 결정 (위임전결 규정)

결재선은 항상 approval이 만듭니다 (상신·재기안 모두). 결재자를 찾는 로직을 각 서비스가 따로 가질 필요가 없습니다.

1. 양식과 `attributes`로 **전결 단계**(최종 결재권자)를 정합니다.
2. user-service 조직도에서 기안자 부서부터 위로 올라가며 각 부서의 부서장을 결재자로 넣고, 전결 단계 부서장에서 멈춥니다.

### 양식별 전결 단계

| 양식 | 기본 | 조건부 상향 | 필요한 `attributes` |
|---|---|---|---|
| `LEAVE` 휴가 | 팀장 | 휴가 일수 5일 이상 → 본부장 | `days` |
| `OVERTIME` 초과근무 | 팀장 | 그 달 누적 12시간(720분) 초과 → 본부장 | `monthlyOvertimeMinutes` (`overtimeMinutes`는 참고용) |
| `TIME_FIX` 근무시간 수정 | 팀장 | 없음 | 없음 |
| `HOLIDAY_SUBST` 휴일대체 | 팀장 | 없음 | 없음 |
| `BUSINESS_TRIP` 출장 | 팀장 | 해외 → 본부장 | `overseas` |
| `GENERAL` 일반 기안 | 팀장 | 100만 원 초과 → 본부장, 1,000만 원 초과 → 대표이사 | `amount` |

- 기준값(5일, 720분, 금액)은 설정값(`app.approval-policy.*`)이라 코드 수정 없이 바뀔 수 있습니다.
- `attributes`가 없거나 값이 빠지면 그 조건은 충족하지 않은 것으로 보고 기본(팀장)으로 처리합니다.
- 보낸 `attributes`는 상신 시점 값으로 문서에 저장되고, 문서 상세의 `attributes`, `approvalLevel`로 확인할 수 있습니다.

### attributes 항목

| 필드 | 타입 | 설명 | 보내는 쪽 |
|---|---|---|---|
| `days` | number | 휴가 일수. 반차는 0.5 | leave-service |
| `overtimeMinutes` | number | 이번 초과근무 신청 시간(분) | attendance-service |
| `monthlyOvertimeMinutes` | number | **이번 신청을 포함한** 그 달 초과근무 누적(분) | attendance-service |
| `overseas` | boolean | 해외 출장 여부 | 결재 화면 |
| `amount` | number | 금액(원) | 결재 화면, ai-service |

모두 0 이상이어야 합니다. 음수면 `400 COMMON_001`.

### 예외 처리

| 상황 | 처리 |
|---|---|
| 기안자가 팀장 | 본인을 빼고 한 단계 위(본부장)부터. 팀장 휴가는 본부장 전결 |
| 기안자가 본부장 | 대표이사가 결재 |
| 기안자가 대표이사 | **자가결재**: 결재자 없이 상신 즉시 승인(`APPROVED`). 출처가 있으면 결과 이벤트도 바로 나감 |
| 부서장이 공석이거나 휴직·퇴직 | 그 위 단계 부서장이 결재 |
| 한 사람이 겸직 | 결재선에 한 번만 |
| 위로 끝까지 가도 전결 단계 이상의 부서장이 없음 | `409 APPROVAL_022`. 조직도(부서장 지정)를 고쳐야 함 |

### 기본 결재선 미리보기

결재 화면이 기안 전에 결재선을 보여줄 때 씁니다. 저장하지 않습니다.

```
GET /api/v1/documents/approval-line-preview?formType=GENERAL&amount=2000000
Authorization: Bearer {access token}
```

쿼리: `formType`(필수), `days`, `overtimeMinutes`, `monthlyOvertimeMinutes`, `overseas`, `amount`(선택). 요청자를 기안자로 봅니다.

```json
{
  "success": true,
  "data": {
    "formType": "GENERAL",
    "approvalLevel": "DIVISION",
    "selfApproval": false,
    "approvalLines": [
      { "empId": 105, "step": 1, "role": "APPROVER" },
      { "empId": 108, "step": 2, "role": "APPROVER" }
    ]
  }
}
```

화면은 이 결과를 보여주고 그대로 상신합니다. 참조자·열람자, 추가 결재자는 상신 후 [결재선 변경 API](#상신-후-결재선-변경)로 넣습니다.

### 상신 후 결재선 변경

규정으로 정한 결재자는 `required: true`로 표시되고(결재선 조회 응답), **빼거나 서로 순서를 바꾸거나 전결로 건너뛸 수 없습니다.**
기안자가 추가하는 결재자·합의자·참조자·열람자는 자유롭게 넣고 뺄 수 있습니다 (결재가 늘어날 뿐 규정을 우회하지 않음).

| API | 내용 | 규칙 |
|---|---|---|
| `POST /{documentId}/approvers` `{empId, step, role}` | 결재자(`APPROVER`)·합의자(`AGREEMENT`) 추가 | 이미 결재가 끝난 순서 앞에는 못 넣음 |
| `DELETE /{documentId}/approvers/{empId}` | 결재자 제외 | 규정상 결재자는 `409 APPROVAL_024` |
| `PATCH /{documentId}/approvers/order` `{empIds}` | 결재자 순서 변경 (전체 목록) | 규정상 결재자끼리의 순서(팀장 → 본부장 → 대표이사)가 바뀌면 `409 APPROVAL_024`. 추가한 결재자는 그 사이 어디든 |
| `POST /{documentId}/final-approve` | 전결 (차례인 결재자가 남은 결재 생략) | 뒤에 규정상 결재자가 남아 있으면 `409 APPROVAL_025`. 규정상 마지막 결재자는 기안자가 추가한 뒤쪽 결재자를 건너뛸 수 있음 |
| `POST·DELETE /{documentId}/referrers`, `/viewers` | 참조자·열람자 추가·제외 | — |

공통: 기안자만, 진행 중 문서만, 기안자 본인·이미 있는 사원 불가(`APPROVAL_002`, `APPROVAL_003`), 재직 중인 사원만(`APPROVAL_015`, `APPROVAL_016`). 경로 앞부분은 `/api/v1/documents`.

재기안(`POST /api/v1/documents/{documentId}/resubmit`, 본문 `{ "title": "..." }`)은 직접 기안 문서만 되고, 결재선을 **재기안 시점의 조직도**로 다시 만듭니다 (그 사이 부서장이 바뀌었으면 새 부서장).

#### 예시

아래는 사원 103(개발팀)이 200만 원 일반 기안(문서 1013)을 올려 결재선이 [팀장 105, 본부장 108]로 만들어진 뒤의 요청입니다. 모두 `Authorization: Bearer {기안자 access token}`.

결재선 조회 (`required: true`가 규정상 결재자)

```
GET /api/v1/documents/1013/approvers
```

```json
{
  "success": true,
  "data": [
    { "lineId": 25, "step": 1, "role": "APPROVER", "action": "PENDING", "empId": 105, "actedAt": null, "required": true },
    { "lineId": 26, "step": 2, "role": "APPROVER", "action": "PENDING", "empId": 108, "actedAt": null, "required": true }
  ],
  "error": null
}
```

참조자·열람자 추가 (`201`, `data`는 `null`). 빼기는 `DELETE /api/v1/documents/1013/referrers/{empId}`

```
POST /api/v1/documents/1013/referrers
{ "empId": 101 }

POST /api/v1/documents/1013/viewers
{ "empId": 104 }
```

결재자·합의자 추가 (`201`). `step`은 기존 결재자와 겹치지 않게, 이미 결재가 끝난 순서보다 뒤로

```
POST /api/v1/documents/1013/approvers
{ "empId": 106, "step": 3, "role": "AGREEMENT" }
```

순서 변경 (`200`). **현재 결재자 전체**를 원하는 순서로. 규정상 결재자끼리(105 → 108)의 순서만 지키면 추가한 106은 어디든

```
PATCH /api/v1/documents/1013/approvers/order
{ "empIds": [106, 105, 108] }     → 200
{ "empIds": [108, 105, 106] }     → 409 APPROVAL_024 (105, 108 순서가 뒤집힘)
```

추가한 결재자 빼기는 되고, 규정상 결재자 빼기는 안 됩니다

```
DELETE /api/v1/documents/1013/approvers/106   → 200
DELETE /api/v1/documents/1013/approvers/108   → 409 APPROVAL_024
```

전결 (본문 없음, 차례인 결재자의 토큰)

```
POST /api/v1/documents/1013/final-approve
```

- 105(팀장)가 전결 → 뒤에 규정상 결재자 108이 남아 있어 `409 APPROVAL_025`
- 105 승인 후 108(본부장)이 전결 → 뒤에 기안자가 추가한 결재자만 있으면 그들은 `SKIPPED`, 문서 `APPROVED`

재기안 (반려된 직접 기안 문서, `200`)

```
POST /api/v1/documents/1013/resubmit
{ "title": "프로젝트 예산 집행 요청 (금액 수정)" }
```

결재선은 요청에 넣지 않습니다. 재기안 시점의 조직도로 다시 만들고 차수가 1 올라갑니다.

오류 응답 형식 (모든 API 공통)

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "APPROVAL_024",
    "message": "위임전결 규정상 결재자는 결재선에서 빼거나 순서를 바꿀 수 없습니다."
  }
}
```

| HTTP | 코드 | 언제 |
|---|---|---|
| 409 | `APPROVAL_024` | 규정상 결재자 제외, 규정상 결재자끼리 순서 변경 |
| 409 | `APPROVAL_025` | 뒤에 규정상 결재자가 남았는데 전결 |
| 409 | `APPROVAL_023` | 출처 문서(휴가·근태·업무 자동화) 재기안 |
| 409 | `APPROVAL_014` | 반려되지 않은 문서 재기안 |
| 409 | `APPROVAL_006` | 진행 중이 아닌 문서의 결재선 변경 |
| 409 | `APPROVAL_008` | 이미 결재한 결재자 제외·순서 변경 |
| 403 | `APPROVAL_005` | 기안자가 아닌 사람의 결재선 변경·재기안 |
| 403 | `APPROVAL_012` | 차례가 아닌 결재자의 승인·반려·전결 |
| 400 | `APPROVAL_002` / `003` | 기안자 본인 / 이미 결재선에 있는 사원 추가 |
| 400 | `APPROVAL_004` | 결재자 추가 시 `step` 중복 |
| 400 | `APPROVAL_010` | 이미 결재가 끝난 순서 앞에 추가, 순서 변경 목록이 현재 결재자 전체와 다름 |
| 400 | `APPROVAL_015` / `016` | 없는 사원 / 재직 중이 아닌 사원 추가 |
| 404 | `APPROVAL_007` | 결재선에 없는 사원 제외 |

## 서비스별 호출 방법

공통 규칙

- **신청한 사원의 access 토큰**으로 호출합니다 (`BearerTokenRelayInterceptor`). 그 사원이 기안자가 되고, 결재선도 그 사원의 부서 기준으로 만들어집니다.
- **`approvalLines`는 보내지 않습니다.** 결재선은 approval이 만듭니다. 보내도 무시됩니다.
- 전결 판단에 필요한 값만 `attributes`에 담습니다 (양식별로 아래 표).
- 타임아웃·503이면 **같은 `requestKey`로** 다시 보내거나 [요청 키로 조회](#요청-키로-문서-조회)합니다.
- 생성 요청 타임아웃은 짧게(연결 3초, 응답 10초 정도). approval이 그 안에서 user-service 조직도를 조회합니다.

| 응답 | 원본 서비스가 할 일 |
|---|---|
| `201` / `200` | `documentId` 저장. 같은 키 재요청이면 `200`으로 처음 문서가 옴 |
| (자가결재) `201`, 응답 형식은 같음 | 대표이사 기안이면 결재자 없이 상신과 함께 승인됩니다. 응답만으로는 알 수 없고, **`APPROVED` 결과 이벤트가 곧바로** 옵니다. 이벤트가 생성 응답 처리보다 먼저 올 수 있으니 [결과 이벤트 가이드](approval-result-event.md#멱등-처리-필수)대로 신청 중(`SUBMITTING`) 상태에서도 반영 |
| `400` | 요청 값 문제. 신청을 실패로 처리하고 `error.message`를 사용자에게 보여줌 |
| `409 APPROVAL_022` | 조직도에 부서장이 없어 결재자를 못 정함. 재시도해도 같음. 관리자에게 부서장 지정 요청 |
| `503 APPROVAL_017` | user-service 장애. 같은 `requestKey`로 재시도 |

반려 후 다시 신청하기

- 출처 문서는 **반려가 최종**입니다. approval 의 재기안(`POST /api/v1/documents/{documentId}/resubmit`)은 직접 기안 문서만 되고, 출처 문서는 `409 APPROVAL_023`입니다.
- `REJECTED` 결과를 받으면 그 신청을 종료(예약한 휴가 일수 반환 등)합니다.
- 사용자가 다시 신청하면 **새 신청**으로 만들고 새 `requestKey`로 결재 문서를 새로 만듭니다. 화면에 "반려된 신청 복사해서 새로 신청" 기능을 두면 재기안처럼 편하게 쓸 수 있습니다 (선택, 각 서비스 화면).

### attendance-service

현재 상신 요청 DTO(`AttendanceRequestSubmitRequest`)가 화면에서 결재선을 받아(`approvalLines @NotEmpty`) 그대로 approval에 넘깁니다.
**approval은 이제 이 값을 무시하고 규정으로 결재선을 만듭니다** (요청은 실패하지 않음). 화면에서 고른 결재선이 쓰이지 않으므로 아래처럼 바꿉니다.

1. `AttendanceRequestSubmitRequest`에서 `approvalLines` 제거 (화면도 결재선 선택 제거, 대신 미리보기 표시)
2. `ApprovalDocumentCreateRequest`에서 `approvalLines`를 빼고 `attributes` 추가
3. `OVERTIME`이면 **이번 신청을 포함한 그 달 초과근무 누적(분)** 을 계산해 담음. 미리보기에서 이미 계산하는 주간 누적(`weeklyTotalMin`)과 같은 방식으로 월 단위
4. `TIME_FIX`, `HOLIDAY_SUBST`는 `attributes` 없이 보냄 (항상 팀장 전결)

```java
public record ApprovalDocumentCreateRequest(
        String title,
        String formType,
        String sourceType,
        Long sourceId,
        UUID requestKey,
        Attributes attributes                 // approvalLines 대신
) {
    /** 전결 판단 값. 없는 값은 보내지 않는다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attributes(Integer overtimeMinutes, Integer monthlyOvertimeMinutes) {
    }

    public static ApprovalDocumentCreateRequest ofAttendance(String title, AttendanceRequestType reqType,
                                                             Long attReqId, Attributes attributes) {
        return new ApprovalDocumentCreateRequest(title, reqType.name(), "ATTENDANCE", attReqId,
                requestKeyOf(attReqId), attributes);
    }
}

// 상신할 때
Attributes attributes = reqType == AttendanceRequestType.OVERTIME
        ? new Attributes(requestedMinutes, monthlyOvertimeMinutesIncludingThis)
        : null;
approvalClient.createDocument(ApprovalDocumentCreateRequest.ofAttendance(title, reqType, attReqId, attributes));
```

보내는 JSON

```json
{
  "title": "초과근무 신청 (2026-11-05 19:00 ~ 22:00)",
  "formType": "OVERTIME",
  "sourceType": "ATTENDANCE",
  "sourceId": 7,
  "requestKey": "b9e4a7d2-5c1f-4e8a-a3b6-2d7c9f1e4a50",
  "attributes": { "overtimeMinutes": 180, "monthlyOvertimeMinutes": 540 }
}
```

`ApprovalClient`(호출·오류 처리)는 바꿀 필요 없습니다.

### leave-service

HTTP 구현체가 아직 없습니다(`UnavailableApprovalClient`). `ApprovalClient` 구현체를 만들 때 `ApprovalCreateCommand`를 아래 JSON으로 바꿔 보냅니다. 결재선은 필요 없습니다.

| 요청 필드 | `ApprovalCreateCommand`에서 |
|---|---|
| `title` | 휴가 종류·기간으로 만든 제목 (예: `연차 신청 (2026-11-10 ~ 2026-11-11)`) |
| `formType`, `sourceType` | `"LEAVE"`, `"LEAVE"` |
| `sourceId` | `requestId` |
| `requestKey` | `approvalRequestId` |
| `attributes.days` | 휴가 일수. `dates`의 `portion`으로 계산: `FULL` 1, `AM_HALF`·`PM_HALF` 0.5, `HOURLY`는 `requestedMinutes / 480` (계산 기준은 leave 담당이 정함) |

```java
BigDecimal days = command.dates().stream()
        .map(d -> switch (d.portion()) {
            case FULL -> BigDecimal.ONE;
            case AM_HALF, PM_HALF -> new BigDecimal("0.5");
            case HOURLY -> BigDecimal.valueOf(d.requestedMinutes()).divide(BigDecimal.valueOf(480), 2, RoundingMode.HALF_UP);
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add);

Map<String, Object> body = Map.of(
        "title", title,
        "formType", "LEAVE",
        "sourceType", "LEAVE",
        "sourceId", command.requestId(),
        "requestKey", command.approvalRequestId(),
        "attributes", Map.of("days", days));

restClient.post().uri("/api/v1/documents").body(body).retrieve()...   // 신청자 토큰 전달
```

| `ApprovalCreationResult.Outcome` | approval 응답 |
|---|---|
| `SUBMITTED_CONFIRMED` | `201` / `200` (`data.documentId` 저장) |
| `NOT_CREATED_CONFIRMED` | `400`, `409` (재시도해도 같은 결과) |
| `UNKNOWN` | 타임아웃, `5xx`. 같은 `requestKey`로 재시도하거나 `findByRequestId` |
| `DRAFT_CREATED` | 쓰지 않음. approval 은 임시저장 문서를 만들지 않고 생성 즉시 상신한다 |

`findByRequestId(approvalRequestId)`는 `GET /api/v1/documents/by-request/{approvalRequestId}`입니다. `200`이면 `SUBMITTED_CONFIRMED`, `404`면 `NOT_CREATED_CONFIRMED`(문서가 안 만들어진 것이 확정).

보내는 JSON

```json
{
  "title": "연차 신청 (2026-11-10 ~ 2026-11-11)",
  "formType": "LEAVE",
  "sourceType": "LEAVE",
  "sourceId": 42,
  "requestKey": "3f6b2c1e-8d4a-4b7f-9c2e-1a5d7e9f0b12",
  "attributes": { "days": 2 }
}
```

### ai-service (업무 자동화)

워크플로우의 결재 단계가 일반 기안(`GENERAL`)을 상신하고, [결과 이벤트](approval-result-event.md)의 `sourceType: AUTOMATION`을 받아 다음 단계로 넘어갑니다.

- **워크플로우를 실행한 사원의 access 토큰**으로 호출합니다. 서비스 계정 토큰으로 부르면 그 계정이 기안자가 되고 결재선도 그 계정 부서 기준으로 만들어집니다.
- `sourceId`는 워크플로우 실행 ID(**숫자**), `requestKey`는 실행(결재 단계)마다 만들어 저장한 UUID입니다.
- 금액이 있으면 `attributes.amount`(원)를 보냅니다. 100만 원 초과 본부장, 1,000만 원 초과 대표이사 전결. 빠지면 팀장 전결입니다.
- 만들 수 있는 양식은 `GENERAL`뿐입니다. 다른 양식은 `400 APPROVAL_019`.
- **반려·회수가 최종**입니다(재기안 `409 APPROVAL_023`). 다시 결재받으려면 새 `requestKey`로 새 문서를 상신합니다.

```json
{
  "title": "서버 증설 구매 품의",
  "formType": "GENERAL",
  "sourceType": "AUTOMATION",
  "sourceId": 501,
  "requestKey": "6a1d3e5b-2f4c-4d8e-9b7a-0c3e5f7a9b21",
  "attributes": { "amount": 5000000 }
}
```

### 결재 화면 (프런트, 일반 기안·출장)

1. 작성 화면에서 양식과 판단 값(출장 `overseas`, 일반 기안 `amount`)이 바뀔 때마다 [미리보기](#기본-결재선-미리보기)를 호출해 결재선을 보여줍니다.
2. 결재선 없이 상신합니다. 참조자·열람자, 추가 결재자·합의자는 상신 직후 [결재선 변경 API](#상신-후-결재선-변경)로 넣습니다.
   결재선 화면에서 `required: true`인 결재자는 삭제·순서 변경 버튼을 숨깁니다.
3. `selfApproval: true`면 "결재 없이 바로 승인됩니다"로 안내합니다.
4. 상신 버튼 중복 클릭 대비로 화면을 열 때 `requestKey`를 하나 만들어 두면 좋습니다 (선택).
5. 문서 상세의 **재기안 버튼은 `status`가 `REJECTED`이고 `sourceType`이 `DIRECT`인 문서에만** 보여줍니다. 휴가·근태·업무 자동화 문서는 각 원본 서비스에서 새로 신청합니다.

```
GET  /api/v1/documents/approval-line-preview?formType=BUSINESS_TRIP&overseas=true
POST /api/v1/documents
{ "title": "싱가포르 컨퍼런스 참석", "formType": "BUSINESS_TRIP", "attributes": { "overseas": true } }
```

## 양식별로 보내야 하는 값

휴가·근태 문서는 해당 서비스의 신청을 거쳐야만 만들 수 있습니다. 결재 화면에서 직접 만들 수 없습니다.

| 누가 보내나 | formType | sourceType | sourceId |
|---|---|---|---|
| 결재 화면 (직접 작성) | `GENERAL` 일반 기안 | `DIRECT` 또는 생략 | 보내지 않음 |
| 결재 화면 (직접 작성) | `BUSINESS_TRIP` 출장 신청 | `DIRECT` 또는 생략 | 보내지 않음 |
| ai-service (업무 자동화) | `GENERAL` 일반 기안 | `AUTOMATION` | 워크플로우 실행 ID |
| leave-service | `LEAVE` 휴가 신청 | `LEAVE` | 휴가 신청 ID (`requestId`) |
| attendance-service | `OVERTIME` 초과근무 신청 | `ATTENDANCE` | 근태 신청 ID |
| attendance-service | `TIME_FIX` 근무시간 수정 신청 | `ATTENDANCE` | 근태 신청 ID |
| attendance-service | `HOLIDAY_SUBST` 휴일대체 신청 | `ATTENDANCE` | 근태 신청 ID |

attendance-service는 신청 종류(`AttendanceRequestType`)와 같은 이름을 `formType`으로 보내면 됩니다.

## 요청 예시

### 결재 화면에서 일반 기안

```json
{
  "title": "프로젝트 예산 집행 요청",
  "formType": "GENERAL",
  "attributes": { "amount": 3000000 }
}
```

300만 원이라 본부장 전결 → 결재선은 [팀장, 본부장]. 참조자가 필요하면 상신 후 `POST /api/v1/documents/{documentId}/referrers`.

leave-service, attendance-service, ai-service 요청 예시는 [서비스별 호출 방법](#서비스별-호출-방법)에 있습니다.

## 응답

`201 Created` (같은 `requestKey`로 다시 보낸 경우 `200 OK`, 본문 형식은 같음)

```json
{
  "success": true,
  "data": {
    "documentId": 128,
    "docNo": "LEAVE-2026-0001"
  }
}
```

| 필드 | 설명 |
|---|---|
| `documentId` | 결재 문서 ID. 이후 조회·결재 API 경로에 씁니다 (`/api/v1/documents/{documentId}`) |
| `docNo` | 문서번호. 화면 표시용. 형식: `{양식}-{연도}-{일련번호 4자리}`. 양식·연도(한국 시간 기준)별로 1번부터 매깁니다 |

신청 쪽 서비스는 `documentId`를 저장해 두면 나중에 결재 문서를 찾을 수 있습니다.

## 요청 키로 문서 조회

생성 결과를 모를 때(타임아웃 등)나 결재 결과 이벤트를 놓쳤을 때 씁니다.

```
GET /api/v1/documents/by-request/{requestKey}
Authorization: Bearer {access token}
```

| 응답 | 의미 |
|---|---|
| `200` | 문서 상세 (`documentId`, `docNo`, `status`, `requestKey`, 결재선, 이력 등) |
| `404` `APPROVAL_001` | 이 키로 만든 문서가 없음. 또는 조회 권한 없음(기안자·결재선 참여자·관리자만 조회 가능) |

`404`면 문서가 만들어지지 않은 것이 확정입니다. 같은 `requestKey`로 생성을 다시 요청하면 됩니다.

## 오류

| HTTP | 코드 | 원인 |
|---|---|---|
| 400 | `COMMON_001` | 필수 값 누락, 잘못된 enum 값 등 |
| 400 | `APPROVAL_015` | 기안자가 user-service 에 없는 사원 |
| 400 | `APPROVAL_018` | `DIRECT`가 아닌데 `sourceId`가 없음, 또는 `DIRECT`인데 `sourceId`가 있음 |
| 400 | `APPROVAL_019` | 위 표와 다른 양식·출처 조합 (예: 결재 화면에서 `LEAVE` 양식 직접 작성) |
| 400 | `APPROVAL_020` | `DIRECT`가 아닌데 `requestKey`가 없음 |
| 409 | `APPROVAL_021` | 같은 `requestKey`로 다른 신청의 문서가 이미 있음 |
| 409 | `APPROVAL_022` | 조직도에서 결재자를 정할 수 없음 (부서장 미지정) |
| 401 | `AUTH_xxx` | 토큰이 없거나 유효하지 않음 |
| 503 | `APPROVAL_017` | 사원·조직 정보를 확인할 수 없음 (user-service 장애). 잠시 후 재시도 |

## 아직 지원하지 않는 것

- 대결(부재 시 대리 결재), 관리자 결재선 변경

다른 양식이나 출처가 필요하면 approval-service 담당자에게 알려 주세요.
