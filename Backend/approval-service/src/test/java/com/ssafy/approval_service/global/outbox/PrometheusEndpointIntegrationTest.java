package com.ssafy.approval_service.global.outbox;

import com.ssafy.approval_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prometheus 가 토큰 없이 /actuator/prometheus 를 수집할 수 있고, 아웃박스 메트릭이 들어 있다.
 * Spring Boot 테스트는 기본으로 메트릭 내보내기를 끄므로 이 테스트에서만 켠다.
 */
@TestPropertySource(properties = "management.defaults.metrics.export.enabled=true")
class PrometheusEndpointIntegrationTest extends AbstractIntegrationTest {

    @Test
    void 토큰_없이_수집할_수_있고_아웃박스_메트릭이_있다() throws Exception {
        String body = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("approval_outbox_pending")
                .contains("approval_outbox_oldest_age_seconds")
                .contains("approval_outbox_published_total")
                .contains("approval_outbox_publish_failures_total")
                .contains("application=\"approval-service\"");
    }

    @Test
    void 다른_actuator_경로는_여전히_토큰이_필요하다() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
