package com.ssafy.approval_service.global.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 아웃박스의 발행 전 이벤트를 Kafka 로 보낸다.
 * <ul>
 *   <li>브로커가 받았다는 확인(ack)을 받은 뒤에만 발행 완료로 표시한다</li>
 *   <li>보내다 실패하면 그 자리에서 멈춘다. 뒤 이벤트가 앞 이벤트를 앞질러 순서가 바뀌지 않게 하기 위해서다.
 *       실패한 이벤트와 그 뒤 이벤트는 다음 주기에 다시 보낸다</li>
 *   <li>보낸 뒤 완료 표시 전에 죽으면 다음 주기에 한 번 더 보낸다 → 최소 한 번 전달(중복 가능). 수신 측이 멱등하게 처리한다</li>
 * </ul>
 */
@Slf4j
@Component
public class OutboxPublisher {

    static final String PUBLISHED_COUNTER = "approval.outbox.published";
    static final String FAILED_COUNTER = "approval.outbox.publish.failures";

    /** 한 번에 보낼 최대 개수 */
    static final int BATCH_SIZE = 100;

    /**
     * 브로커 확인을 기다리는 최대 시간. 프로듀서의 delivery.timeout.ms(10초)보다 길게 둬서,
     * 프로듀서가 성공/실패를 확정한 뒤에 판단한다 (먼저 포기하면 나중에 실제로 전송돼 결과를 모르게 됨)
     */
    static final long SEND_TIMEOUT_SECONDS = 15;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter publishedCounter;     // 지금까지 발행 성공 수 (카운터: 계속 증가)
    private final Counter failedCounter;        // 지금까지 발행 실패 수

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.publishedCounter = Counter.builder(PUBLISHED_COUNTER)
                .description("Kafka 로 발행한 아웃박스 이벤트 수")
                .register(meterRegistry);
        this.failedCounter = Counter.builder(FAILED_COUNTER)
                .description("아웃박스 이벤트 발행 실패 수")
                .register(meterRegistry);
    }

    /** 발행 전 이벤트를 보내고, 보낸 개수를 돌려준다 */
    @Transactional
    public int publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.lockUnpublished(BATCH_SIZE);
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 브로커 장애 등. 여기서 멈추고 다음 주기에 이 이벤트부터 다시 보낸다
                log.warn("아웃박스 발행 실패, 다음 주기에 재시도: id={}, eventId={}, cause={}",
                        event.getId(), event.getEventId(), e.toString());
                failedCounter.increment();
                break;
            }
            event.markPublished();
            publishedCounter.increment();
            published++;
        }
        return published;
    }

    /** 발행이 끝난 지 retentionDays 일이 지난 이벤트를 지운다 (그동안은 문제 추적용으로 남김) */
    @Transactional
    public int deletePublishedOlderThan(int retentionDays) {
        return outboxEventRepository.deletePublishedBefore(OffsetDateTime.now().minusDays(retentionDays));
    }
}
