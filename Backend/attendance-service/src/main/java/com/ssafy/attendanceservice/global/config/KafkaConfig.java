package com.ssafy.attendanceservice.global.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka 수신 실패 처리 (DOCS/approval-result-event.md).
 * 1초 → 2초 → 4초 재시도 후에도 실패하면 "원래 토픽.DLT"의 같은 파티션으로 옮긴다.
 * 옮기지 않으면 실패한 메시지가 같은 파티션의 뒤 메시지를 모두 막는다.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    // DLT 토픽 이름: 결재 서비스가 만드는 approval.document.result.DLT (파티션 수도 원래 토픽과 같음).
    // spring-kafka 4.x의 기본값은 "-dlt"라 직접 지정한다 (휴가 서비스와 같은 방식)
    private static final String DLT_SUFFIX = ".DLT";

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, failure) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2.0);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // 재시도 중 실패 원인은 기본으로 로그에 남지 않으므로 시도마다 남긴다
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka 메시지 처리 실패 ({}번째): topic={}, partition={}, offset={}, key={}",
                        deliveryAttempt, record.topic(), record.partition(), record.offset(), record.key(), ex));
        return errorHandler;
    }
}
