package com.ssafy.leaveservice.domain.request.messaging;

import com.ssafy.leaveservice.domain.request.dto.LeaveApprovalDecision;
import com.ssafy.leaveservice.domain.request.service.LeaveApprovalSettlementService;
import com.ssafy.leaveservice.domain.request.service.LeaveApprovalSettlementService.Outcome;
import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "leave.test.database-owner=ApprovalResultKafkaIntegrationTest",
        "leave.approval.results.enabled=true",
        "leave.approval.results.contract-verified=true",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
@Testcontainers
class ApprovalResultKafkaIntegrationTest {
    private static final String TOPIC = ApprovalResultListener.TOPIC;
    private static final String DLT = TOPIC + ".DLT";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void brokerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.properties.allow.auto.create.topics", () -> false);
    }

    @BeforeAll
    static void createTopics() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(DLT, 1, (short) 1)))
                    .all().get(20, TimeUnit.SECONDS);
        }
    }

    @Autowired
    KafkaTemplate<Object, Object> kafka;

    @MockitoBean
    LeaveApprovalSettlementService settlement;

    @Test
    void consumesRetriesAndPublishesOriginalFailuresToDlt() throws Exception {
        AtomicInteger approvalAttempts = new AtomicInteger();
        when(settlement.settle(any())).thenAnswer(invocation -> {
            LeaveApprovalDecision decision = invocation.getArgument(0);
            return switch (decision.status()) {
                case "APPROVED" -> switch (approvalAttempts.incrementAndGet()) {
                    case 1 -> Outcome.AWAITING_DOCUMENT_LINK;
                    case 2 -> Outcome.APPLIED;
                    default -> Outcome.ALREADY_APPLIED;
                };
                case "REJECTED" -> Outcome.REQUEST_NOT_FOUND;
                default -> Outcome.CONFLICT;
            };
        });

        String approved = ApprovalResultListenerTest.payload("APPROVED");
        String rejected = ApprovalResultListenerTest.payload("REJECTED");
        String cancelled = ApprovalResultListenerTest.payload("CANCEL");
        kafka.send(TOPIC, "42", approved).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, "42", approved).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, "42", rejected).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, "42", cancelled).get(10, TimeUnit.SECONDS);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of("bootstrap.servers", KAFKA.getBootstrapServers()),
                new StringDeserializer(), new StringDeserializer()
        )) {
            TopicPartition partition = new TopicPartition(DLT, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            List<String> failedPayloads = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (failedPayloads.size() < 2 && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    failedPayloads.add(record.value());
                }
            }
            assertThat(failedPayloads).containsExactly(rejected, cancelled);
        }
        verify(settlement, timeout(1000).times(3)).settle(argThat(decision -> decision.status().equals("APPROVED")));
        verify(settlement, timeout(1000).times(4)).settle(argThat(decision -> decision.status().equals("REJECTED")));
        verify(settlement, timeout(1000).times(1)).settle(argThat(decision -> decision.status().equals("CANCEL")));
    }
}
