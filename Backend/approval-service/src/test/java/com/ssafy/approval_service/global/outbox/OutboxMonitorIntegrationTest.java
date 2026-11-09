package com.ssafy.approval_service.global.outbox;

import com.ssafy.approval_service.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 DB 에서 감시 쿼리와 게이지 등록을 확인한다.
 * 다른 테스트와 아웃박스를 공유하므로 절대값이 아니라 "넣은 만큼 늘었는지"로 확인한다.
 */
class OutboxMonitorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    OutboxMonitor outboxMonitor;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void 미발행_수와_가장_오래된_이벤트를_조회하고_게이지에_반영한다() {
        long before = outboxEventRepository.countByPublishedAtIsNull();

        OutboxEvent first = outboxEventRepository.save(new OutboxEvent(UUID.randomUUID(), "t", "1", "{}"));
        outboxEventRepository.save(new OutboxEvent(UUID.randomUUID(), "t", "2", "{}"));
        OutboxEvent published = new OutboxEvent(UUID.randomUUID(), "t", "3", "{}");
        published.markPublished();
        outboxEventRepository.save(published);

        assertThat(outboxEventRepository.countByPublishedAtIsNull()).isEqualTo(before + 2);
        // 가장 오래된 미발행 이벤트는 방금 넣은 것보다 앞이거나 같다 (id 순)
        assertThat(outboxEventRepository.findFirstByPublishedAtIsNullOrderByIdAsc())
                .get().extracting(OutboxEvent::getId).matches(id -> id <= first.getId());

        outboxMonitor.check();

        assertThat(meterRegistry.get(OutboxMonitor.PENDING_GAUGE).gauge().value()).isEqualTo(before + 2);
        assertThat(meterRegistry.find(OutboxPublisher.PUBLISHED_COUNTER).counter()).isNotNull();
        assertThat(meterRegistry.find(OutboxPublisher.FAILED_COUNTER).counter()).isNotNull();
    }
}
