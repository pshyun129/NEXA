package com.ssafy.approval_service.domain.approval.event;

import com.ssafy.approval_service.domain.document.model.entity.Document;
import com.ssafy.approval_service.domain.document.model.entity.enums.SourceType;
import com.ssafy.approval_service.global.outbox.KafkaTopicConfig;
import com.ssafy.approval_service.global.outbox.OutboxEvent;
import com.ssafy.approval_service.global.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결재가 최종 처리(승인·반려·취소)되면 결과 이벤트를 아웃박스에 기록한다.
 * Kafka 로 직접 보내지 않는다. 결재 처리와 같은 트랜잭션에서 기록만 하고, 발행은 OutboxPublisher 가 한다.
 * → 결재 처리가 롤백되면 이벤트도 함께 사라지고, 커밋되면 이벤트도 반드시 남는다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalResultEventRecorder {

    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;

    /** 출처가 있는 문서만 기록한다. 직접 기안 문서는 결과를 받을 원본 서비스가 없다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompleted(Document document) {
        if (document.getSourceType() == SourceType.DIRECT) {
            return;
        }
        ApprovalResultEvent event = ApprovalResultEvent.completed(document);
        outboxEventRepository.save(new OutboxEvent(
                event.eventId(),
                KafkaTopicConfig.APPROVAL_RESULT_TOPIC,
                String.valueOf(document.getDocId()),   // 같은 문서의 이벤트는 같은 파티션 → 순서 보장
                jsonMapper.writeValueAsString(event)));
    }
}
