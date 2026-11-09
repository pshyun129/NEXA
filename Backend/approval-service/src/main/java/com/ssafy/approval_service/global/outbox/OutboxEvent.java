package com.ssafy.approval_service.global.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 아웃박스에 쌓인 발행 대기 이벤트 (approval_outbox).
 * 업무 트랜잭션 안에서 저장하고, {@link OutboxPublisher} 가 나중에 Kafka 로 보낸 뒤 발행 완료로 표시한다.
 */
@Entity
@Table(name = "approval_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                        // 발행 순서

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;                   // 이벤트 고유 ID (수신 측 중복 판단용)

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "message_key", nullable = false)
    private String messageKey;              // 같은 키는 같은 파티션으로 가서 순서가 보장된다

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;                 // 이벤트 JSON

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;     // null 이면 아직 발행 전

    public OutboxEvent(UUID eventId, String topic, String messageKey, String payload) {
        this.eventId = eventId;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = OffsetDateTime.now();
    }

    public void markPublished() {
        this.publishedAt = OffsetDateTime.now();
    }

    public boolean isPublished() {
        return publishedAt != null;
    }
}
