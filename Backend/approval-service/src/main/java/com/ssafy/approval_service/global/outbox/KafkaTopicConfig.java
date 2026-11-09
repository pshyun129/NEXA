package com.ssafy.approval_service.global.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * approval 이 소유하는 토픽. 앱이 뜰 때 KafkaAdmin 이 없으면 만들고, 있으면 그대로 둔다.
 * 브로커의 토픽 자동 생성은 꺼 두고 여기서만 만든다 (오타 토픽이 조용히 생기는 사고 방지, 파티션 수를 코드로 관리)
 */
@Configuration
public class KafkaTopicConfig {

    /** 결재 결과 토픽. 수신: leave-service, attendance-service (각자 자기 sourceType 만 처리) */
    public static final String APPROVAL_RESULT_TOPIC = "approval.document.result";

    /** 수신 측이 계속 처리에 실패한 메시지를 옮기는 곳 (Spring Kafka DeadLetterPublishingRecoverer 기본 이름 규칙) */
    public static final String APPROVAL_RESULT_DLT = APPROVAL_RESULT_TOPIC + ".DLT";

    /** 수신 인스턴스를 최대 3개까지 병렬로 둘 수 있다. 파티션은 늘릴 수만 있고 줄일 수 없어 작게 시작 */
    static final int PARTITIONS = 3;

    /** 브로커가 1대라 복제 계수 1이 최대. 운영에서 브로커 3대 이상이면 3 */
    static final short REPLICAS = 1;

    @Bean
    NewTopic approvalResultTopic() {
        return TopicBuilder.name(APPROVAL_RESULT_TOPIC).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /** DLT 는 원래 토픽과 파티션 수를 맞춘다 (실패한 메시지를 같은 파티션 번호로 옮기기 때문) */
    @Bean
    NewTopic approvalResultDeadLetterTopic() {
        return TopicBuilder.name(APPROVAL_RESULT_DLT).partitions(PARTITIONS).replicas(REPLICAS).build();
    }
}
