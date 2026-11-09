package com.ssafy.approval_service.global.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 발행 실패 시 동작: 실패한 곳에서 멈추고, 성공한 것만 발행 완료로 표시한다 */
class OutboxPublisherTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OutboxPublisher publisher = new OutboxPublisher(repository, kafkaTemplate, meterRegistry);

    private double count(String name) {
        return meterRegistry.get(name).counter().count();
    }

    private static OutboxEvent event(long id, String key) {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "topic", key, "{}");
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> fail() {
        return CompletableFuture.failedFuture(new RuntimeException("broker down"));
    }

    @Test
    void 모두_성공하면_모두_발행_완료() {
        OutboxEvent a = event(1, "10");
        OutboxEvent b = event(2, "11");
        when(repository.lockUnpublished(anyInt())).thenReturn(List.of(a, b));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(ok());

        assertThat(publisher.publishPending()).isEqualTo(2);
        assertThat(a.isPublished()).isTrue();
        assertThat(b.isPublished()).isTrue();
        assertThat(count(OutboxPublisher.PUBLISHED_COUNTER)).isEqualTo(2);
        assertThat(count(OutboxPublisher.FAILED_COUNTER)).isZero();
    }

    @Test
    void 중간에_실패하면_거기서_멈추고_뒤_이벤트는_보내지_않는다() {
        OutboxEvent a = event(1, "10");
        OutboxEvent b = event(2, "11");
        OutboxEvent c = event(3, "12");
        when(repository.lockUnpublished(anyInt())).thenReturn(List.of(a, b, c));
        when(kafkaTemplate.send(anyString(), eq("10"), anyString())).thenReturn(ok());
        when(kafkaTemplate.send(anyString(), eq("11"), anyString())).thenReturn(fail());

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(a.isPublished()).isTrue();
        assertThat(b.isPublished()).as("실패한 이벤트는 다음 주기에 다시 보낸다").isFalse();
        assertThat(c.isPublished()).isFalse();
        // 순서가 바뀌지 않도록 실패 뒤 이벤트는 시도하지 않는다
        verify(kafkaTemplate, never()).send(anyString(), eq("12"), anyString());
        assertThat(count(OutboxPublisher.PUBLISHED_COUNTER)).isEqualTo(1);
        assertThat(count(OutboxPublisher.FAILED_COUNTER)).isEqualTo(1);
    }

    @Test
    void send_자체가_예외를_던져도_멈춘다() {
        // 브로커가 없으면 max.block.ms 뒤 send() 가 바로 예외를 던진다
        OutboxEvent a = event(1, "10");
        when(repository.lockUnpublished(anyInt())).thenReturn(List.of(a));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new org.apache.kafka.common.KafkaException("timeout"));

        assertThat(publisher.publishPending()).isZero();
        assertThat(a.isPublished()).isFalse();
    }
}
