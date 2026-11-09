package com.ssafy.approval_service.global.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 발행 지연 감시: 게이지 값과 지연 판단 */
class OutboxMonitorTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-02T14:00:00+09:00");

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OutboxMonitor monitor = new OutboxMonitor(repository, meterRegistry, Duration.ofMinutes(5));

    private static OutboxEvent eventCreatedAt(OffsetDateTime createdAt) {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "topic", "1", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        ReflectionTestUtils.setField(event, "createdAt", createdAt);
        return event;
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    @Test
    void 쌓인_게_없으면_0이고_지연_아님() {
        when(repository.countByPublishedAtIsNull()).thenReturn(0L);

        assertThat(monitor.check(NOW)).isFalse();
        assertThat(gauge(OutboxMonitor.PENDING_GAUGE)).isZero();
        assertThat(gauge(OutboxMonitor.OLDEST_AGE_GAUGE)).isZero();
        verify(repository, never()).findFirstByPublishedAtIsNullOrderByIdAsc();
    }

    @Test
    void 기준_시간_안이면_게이지만_갱신하고_지연_아님() {
        when(repository.countByPublishedAtIsNull()).thenReturn(3L);
        when(repository.findFirstByPublishedAtIsNullOrderByIdAsc())
                .thenReturn(Optional.of(eventCreatedAt(NOW.minusSeconds(30))));

        assertThat(monitor.check(NOW)).isFalse();
        assertThat(gauge(OutboxMonitor.PENDING_GAUGE)).isEqualTo(3);
        assertThat(gauge(OutboxMonitor.OLDEST_AGE_GAUGE)).isEqualTo(30);
    }

    @Test
    void 가장_오래된_이벤트가_기준_시간을_넘기면_지연() {
        when(repository.countByPublishedAtIsNull()).thenReturn(12L);
        when(repository.findFirstByPublishedAtIsNullOrderByIdAsc())
                .thenReturn(Optional.of(eventCreatedAt(NOW.minusMinutes(7))));

        assertThat(monitor.check(NOW)).isTrue();
        assertThat(gauge(OutboxMonitor.PENDING_GAUGE)).isEqualTo(12);
        assertThat(gauge(OutboxMonitor.OLDEST_AGE_GAUGE)).isEqualTo(7 * 60);
    }

    @Test
    void 밀렸다가_다_나가면_게이지가_0으로_돌아온다() {
        when(repository.countByPublishedAtIsNull()).thenReturn(5L);
        when(repository.findFirstByPublishedAtIsNullOrderByIdAsc())
                .thenReturn(Optional.of(eventCreatedAt(NOW.minusMinutes(10))));
        monitor.check(NOW);

        when(repository.countByPublishedAtIsNull()).thenReturn(0L);
        assertThat(monitor.check(NOW.plusMinutes(1))).isFalse();
        assertThat(gauge(OutboxMonitor.PENDING_GAUGE)).isZero();
        assertThat(gauge(OutboxMonitor.OLDEST_AGE_GAUGE)).isZero();
    }
}
