package com.ssafy.approval_service.global.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 아웃박스 발행 지연 감시.
 * <ul>
 *   <li>게이지 2개: 발행 전 이벤트 수, 가장 오래 기다린 이벤트의 대기 시간(초).
 *       지금은 수집기(Prometheus)가 없어 볼 곳이 없지만, 생기면 /actuator/prometheus 로 바로 노출된다</li>
 *   <li>가장 오래 기다린 이벤트가 기준 시간(app.outbox.alert-threshold, 기본 5분)을 넘으면 ERROR 로그.
 *       브로커 장애로 결재 결과가 원본 서비스에 전달되지 못하고 있다는 뜻이다</li>
 * </ul>
 * 게이지는 조회할 때마다 DB 를 읽지 않고, 주기 점검(check)이 갱신한 값을 돌려준다.
 */
@Slf4j
@Component
public class OutboxMonitor {

    static final String PENDING_GAUGE = "approval.outbox.pending";
    static final String OLDEST_AGE_GAUGE = "approval.outbox.oldest.age";

    private final OutboxEventRepository outboxEventRepository;
    private final Duration alertThreshold;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public OutboxMonitor(OutboxEventRepository outboxEventRepository,
                         MeterRegistry meterRegistry,
                         @Value("${app.outbox.alert-threshold:5m}") Duration alertThreshold) {
        this.outboxEventRepository = outboxEventRepository;
        this.alertThreshold = alertThreshold;
        Gauge.builder(PENDING_GAUGE, pending, AtomicLong::get)
                .description("발행 전 아웃박스 이벤트 수")
                .register(meterRegistry);
        Gauge.builder(OLDEST_AGE_GAUGE, oldestAgeSeconds, AtomicLong::get)
                .description("가장 오래 기다린 발행 전 이벤트의 대기 시간")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /** 게이지를 갱신하고, 발행이 기준 시간 넘게 밀려 있으면 ERROR 로그를 남긴다. 밀려 있으면 true */
    @Transactional(readOnly = true)
    public boolean check() {
        return check(OffsetDateTime.now());
    }

    boolean check(OffsetDateTime now) {
        long count = outboxEventRepository.countByPublishedAtIsNull();
        Optional<OutboxEvent> oldest = count == 0
                ? Optional.empty()
                : outboxEventRepository.findFirstByPublishedAtIsNullOrderByIdAsc();
        long ageSeconds = oldest
                .map(event -> Math.max(0, Duration.between(event.getCreatedAt(), now).toSeconds()))
                .orElse(0L);

        pending.set(count);
        oldestAgeSeconds.set(ageSeconds);

        boolean delayed = oldest.isPresent() && ageSeconds >= alertThreshold.toSeconds();
        if (delayed) {
            OutboxEvent event = oldest.get();
            log.error("아웃박스 발행 지연: 미발행 {}건, 가장 오래된 이벤트 id={} eventId={} {}초째 대기 (기준 {}초). "
                            + "Kafka 브로커 상태를 확인하세요",
                    count, event.getId(), event.getEventId(), ageSeconds, alertThreshold.toSeconds());
        }
        return delayed;
    }

    long pendingValue() {
        return pending.get();
    }

    long oldestAgeSecondsValue() {
        return oldestAgeSeconds.get();
    }
}
