# 결재 결과 이벤트 수신 가이드 (approval-service → Kafka)

결재가 끝났을 때 approval-service가 Kafka로 보내는 **결과 이벤트**를 받는 방법입니다.
대상: 결재를 요청하는 서비스 (leave-service, attendance-service).

> 결재 문서 생성 방법은 `DOCS/approval-document-source.md`, 설계 배경은 `DOCS/approval-integration-decisions.md` (D1) 참고.

## 요약

| 항목 | 값 |
|---|---|
| 토픽 | `approval.document.result` (파티션 3) |
| DLT | `approval.document.result.DLT` (파티션 3, approval이 생성) |
| 메시지 키 | `documentId` (문자열). 같은 문서의 이벤트는 순서가 보장됨 |
| 메시지 값 | JSON 문자열 (아래 형식) |
| 언제 보내나 | **출처가 있는 문서**(`sourceType`이 `LEAVE`/`ATTENDANCE`/`AUTOMATION`)가 최종 승인·전결·반려·회수됐을 때. 중간 결재, 직접 기안 문서는 보내지 않음 |
| 전달 보장 | **최소 한 번(at-least-once)**. 유실은 없지만 같은 이벤트가 두 번 올 수 있음 → 수신 측 멱등 처리 필수 |
| 로컬 브로커 | approval-service `compose.yaml`의 `kafka`. 호스트에서 `localhost:19092` |

## 이벤트 형식

```json
{
  "eventId": "6f1c2a9e-0b7d-4c3f-9a51-2e8d7c4b1f03",
  "eventVersion": 1,
  "eventType": "DOCUMENT_COMPLETED",
  "occurredAt": "2026-11-02T13:20:01.123+09:00",
  "documentId": 128,
  "docNo": "LEAVE-2026-0001",
  "formType": "LEAVE",
  "sourceType": "LEAVE",
  "sourceId": 42,
  "requestKey": "3f6b2c1e-8d4a-4b7f-9c2e-1a5d7e9f0b12",
  "status": "APPROVED"
}
```

| 필드 | 설명 |
|---|---|
| `eventId` | 이벤트마다 고유. 처리 이력을 남길 때 키로 사용 |
| `eventVersion` | 형식 버전. 필드 **추가**는 버전을 올리지 않음 → 모르는 필드는 무시하도록 설정 |
| `eventType` | 현재 `DOCUMENT_COMPLETED` 하나 |
| `occurredAt` | 결재가 끝난 시각 |
| `documentId`, `docNo`, `formType` | 결재 문서 |
| `sourceType` | `LEAVE` \| `ATTENDANCE` \| `AUTOMATION`(ai-service 업무 자동화). **자기 서비스 것이 아니면 무시**. 값이 늘 수 있으니 enum 이 아니라 문자열로 받습니다 |
| `sourceId` | 결재 문서를 만들 때 보낸 원본 신청 ID |
| `requestKey` | 결재 문서를 만들 때 보낸 요청 키 (UUID) |
| `status` | `APPROVED`(승인·전결) \| `REJECTED`(반려) \| `CANCEL`(기안자 회수) |

> **`APPROVED`·`REJECTED`·`CANCEL` 모두 최종입니다** (결정 문서 D1). 출처 문서(휴가·근태·업무 자동화)는 approval 에서 재기안할 수 없으므로(`409 APPROVAL_023`), `REJECTED`를 받은 뒤 같은 문서의 결과가 다시 오지 않습니다.
> 반려된 신청을 다시 하려면 원본 서비스에서 **새 신청**(새 원본 ID, 새 `requestKey`)으로 결재 문서를 새로 만듭니다.

## 수신 설정 (Spring Boot 4 / spring-kafka 4 기준 예시)

```groovy
implementation 'org.springframework.boot:spring-boot-starter-kafka'
```

브로커 주소(`spring.kafka.bootstrap-servers`)는 **config-server 공통 설정**(`nexa-config/application.yml`, 값 `kafka:9092`)에서 내려옵니다.
config 클라이언트인 서비스는 공용 환경에서 따로 설정하지 않아도 됩니다. 아래 첫 줄은 로컬 실행용 기본값입니다.

```properties
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:19092}   # 공용 환경에선 config-server 값이 덮어씀
spring.kafka.consumer.group-id=leave-service           # 서비스 이름. 서비스마다 다르게
spring.kafka.consumer.auto-offset-reset=earliest        # 처음 붙을 때 처음부터 읽음
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer
```

프로듀서 설정은 DLT로 실패 메시지를 옮길 때 씁니다.

### 실패 처리: 재시도 후 DLT

```java
@Bean
DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
    // 기본으로 "원래 토픽.DLT"의 같은 파티션으로 보낸다
    var recoverer = new DeadLetterPublishingRecoverer(template);
    var backOff = new ExponentialBackOffWithMaxRetries(3);   // 1s → 2s → 4s
    backOff.setInitialInterval(1000);
    backOff.setMultiplier(2.0);
    return new DefaultErrorHandler(recoverer, backOff);
}
```

Spring Boot는 `DefaultErrorHandler` 빈이 하나 있으면 리스너 컨테이너에 자동으로 연결합니다.
계속 실패하는 메시지를 DLT로 옮기지 않으면, 그 메시지가 같은 파티션의 뒤 메시지를 모두 막습니다.

### 리스너

```java
@KafkaListener(topics = "approval.document.result")
void onApprovalResult(String payload) {
    ApprovalResultEvent event = jsonMapper.readValue(payload, ApprovalResultEvent.class);
    if (!"LEAVE".equals(event.sourceType())) {
        return;                                    // 다른 서비스 것
    }
    leaveRequestService.applyApprovalResult(event.sourceId(), event.status());
}

@JsonIgnoreProperties(ignoreUnknown = true)          // 필드가 추가돼도 깨지지 않게
record ApprovalResultEvent(String eventId, Long documentId, String sourceType, Long sourceId,
                           String requestKey, String status) {
}
```

### 멱등 처리 (필수)

같은 이벤트가 두 번 올 수 있으므로 **두 번 처리해도 결과가 같아야** 합니다.

**추천: 상태 기반** — 결과 이벤트가 모두 최종 상태라, 신청 상태만 보면 이미 반영했는지 알 수 있습니다.

```java
@Transactional
public void applyApprovalResult(Long requestId, String status) {
    LeaveRequest request = repository.findByIdForUpdate(requestId);   // 동시 처리 대비 잠금
    if (!request.isWaitingApproval()) {                              // SUBMITTING 또는 SUBMITTED
        return;                                                      // 이미 반영됨 → 무시
    }
    switch (status) {
        case "APPROVED" -> request.approve();   // 연차 차감 등
        case "REJECTED" -> request.reject();
        case "CANCEL" -> request.cancel();
    }
}
```

**결재 대기에는 "결재 요청 중" 상태도 포함합니다** (leave 의 `SUBMITTING`, attendance 의 같은 의미 상태).
대표이사 기안(자가결재)은 상신과 함께 승인되어 `APPROVED` 이벤트가 바로 나가므로, 생성 응답(201)을 반영하기 전에 이벤트가 먼저 올 수 있습니다.
`SUBMITTED`일 때만 반영하면 이 이벤트를 "이미 반영됨"으로 무시해 놓칩니다. 이벤트의 `sourceId`·`requestKey`로 신청을 찾으므로 `documentId`를 아직 저장하지 않았어도 반영할 수 있습니다.

처리 이력이 필요하면 `eventId`를 유니크 키로 하는 테이블에 기록하는 방식도 있습니다(어떤 방식을 쓸지는 각 서비스 결정).

## 놓친 이벤트 복구

Kafka 장애로 오래 못 받았거나 메시지가 DLT로 갔다면, 결재 문서를 직접 조회해 상태를 맞춥니다.

```
GET /api/v1/documents/by-request/{requestKey}   → status: INPROGRESS | APPROVED | REJECTED | CANCEL
```

예: 결재 대기로 오래(하루 이상) 남은 신청을 주기적으로 조회해 이미 끝난 것이 있으면 반영.

## 로컬에서 확인

```bash
cd Backend/approval-service
docker compose up -d postgres kafka

# Git Bash 에서는 경로 자동 변환을 끈다
export MSYS_NO_PATHCONV=1
docker exec approval-service-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic approval.document.result \
  --from-beginning --property print.key=true

# 내 컨슈머 그룹이 어디까지 읽었나 (LAG = 아직 안 읽은 수)
docker exec approval-service-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group leave-service
```

토픽은 approval-service가 기동할 때 만듭니다. approval을 한 번도 띄우지 않았다면 토픽이 없습니다.
