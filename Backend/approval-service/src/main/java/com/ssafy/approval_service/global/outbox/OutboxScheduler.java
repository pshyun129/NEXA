package com.ssafy.approval_service.global.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 아웃박스 발행·정리 주기 실행. app.outbox.scheduler.enabled=false 면 등록하지 않는다
 * (테스트는 끄고 {@link OutboxPublisher} 를 직접 호출해 결과를 확인한다)
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.outbox.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private static final int RETENTION_DAYS = 7;

    private final OutboxPublisher outboxPublisher;
    private final OutboxMonitor outboxMonitor;

    /** 이전 실행이 끝나고 1초 뒤 다시 실행 (fixedDelay 라 실행이 겹치지 않는다) */
    @Scheduled(fixedDelayString = "${app.outbox.publish-interval:1000}")
    public void publish() {
        int published = outboxPublisher.publishPending();
        if (published > 0) {
            log.debug("아웃박스 이벤트 {}건 발행", published);
        }
    }

    /** 발행 지연 점검 (기본 1분마다). 게이지를 갱신하고, 기준 시간 넘게 밀려 있으면 ERROR 로그 */
    @Scheduled(fixedDelayString = "${app.outbox.monitor-interval:60000}",
            initialDelayString = "${app.outbox.monitor-interval:60000}")
    public void monitor() {
        outboxMonitor.check();
    }

    /** 매일 새벽 4시에 오래된 발행 완료 이벤트 정리 */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void cleanUp() {
        int deleted = outboxPublisher.deletePublishedOlderThan(RETENTION_DAYS);
        log.info("발행 완료 후 {}일 지난 아웃박스 이벤트 {}건 삭제", RETENTION_DAYS, deleted);
    }
}
