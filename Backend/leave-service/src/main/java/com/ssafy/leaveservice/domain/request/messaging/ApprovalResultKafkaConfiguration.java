package com.ssafy.leaveservice.domain.request.messaging;

import com.ssafy.leaveservice.domain.request.service.LeaveApprovalSettlementService;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "leave.approval.results", name = {"enabled", "contract-verified"}, havingValue = "true")
class ApprovalResultKafkaConfiguration {

    @Bean
    ApprovalResultListener approvalResultListener(
            JsonMapper jsonMapper,
            LeaveApprovalSettlementService settlementService,
            @Value("${spring.kafka.bootstrap-servers:}") String bootstrapServers
    ) {
        Assert.hasText(bootstrapServers, "Approval result consumer requires explicit Kafka bootstrap servers");
        return new ApprovalResultListener(jsonMapper, settlementService);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> approvalResultListenerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, failure) -> new TopicPartition(ApprovalResultListener.TOPIC + ".DLT", record.partition())
        );
        recoverer.setFailIfSendResultIsError(true);
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2);
        backOff.setMaxInterval(4000);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(ApprovalResultListener.InvalidResult.class);

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
