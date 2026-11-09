package com.ssafy.leaveservice.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Slf4j
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres(@Value("${leave.test.database-owner}") String owner) {
        // 소유 클래스가 다른 Context는 별도 DB를 사용하고, 종료는 Spring에 맡긴다.
        return new PostgreSQLContainer("postgres:17-alpine") {
            @Override
            public void stop() {
                log.info("LEAVE_TEST_DATABASE stop-start owner={} container={}", owner, getContainerId());
                super.stop();
                log.info("LEAVE_TEST_DATABASE stop-end owner={}", owner);
            }
        }.withLabel("leave.test.owner", owner);
    }
}
