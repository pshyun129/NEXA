package com.ssafy.leaveservice.client.approval;

import com.ssafy.leaveservice.global.config.LeaveApprovalConfiguration;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ApprovalConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FakeApprovalClientConfiguration.class, LeaveApprovalConfiguration.class)
            .withPropertyValues("leave.approval.connect-timeout=2s", "leave.approval.read-timeout=3s");

    @Test
    void defaultIsDisabledAndOnlyUnavailableClientExists() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ApprovalClient.class);
            assertThat(context.getBean(ApprovalProperties.class).enabled()).isFalse();
            assertThat(context.getBean(ApprovalProperties.class).connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(context.getBean(ApprovalClient.class)).isInstanceOf(UnavailableApprovalClient.class);
            assertThat(context.getBean(ApprovalClient.class).isAvailable()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"prod", "production", "dev,prod", "dev,production", "test,prod", "test,production"})
    void productionNeverRegistersTestFakeEvenWhenFlagEnabled(String profiles) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles.split(",")))
                .withPropertyValues("leave.approval.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ApprovalClient.class);
                    assertThat(context).doesNotHaveBean(FakeApprovalClientConfiguration.FakeApprovalClient.class);
                    assertThat(context.getBean(ApprovalClient.class).isAvailable()).isFalse();
                });
    }

    @Test
    void explicitTestProfileMayInjectFakeButItIsNotAnHttpImplementation() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues("leave.approval.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ApprovalClient.class);
                    assertThat(context.getBean(ApprovalClient.class)).isInstanceOf(FakeApprovalClientConfiguration.FakeApprovalClient.class);
                });
    }

    @Test
    void enablingFlagAloneCannotMakeMissingHttpImplementationAvailable() {
        runner.withPropertyValues("leave.approval.enabled=true", "leave.approval.base-url=http://127.0.0.1:1")
                .run(context -> assertThat(context.getBean(ApprovalClient.class).isAvailable()).isFalse());
    }

    @Test
    void serviceIdIsUsedWithoutBaseUrlOnlyAfterAllFlagsAreEnabled() {
        com.ssafy.leaveservice.client.LeaveRestClientFactory factory = org.mockito.Mockito.mock(
                com.ssafy.leaveservice.client.LeaveRestClientFactory.class);
        org.mockito.Mockito.when(factory.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.web.client.RestClient.create());
        runner.withBean(com.ssafy.leaveservice.client.LeaveRestClientFactory.class, () -> factory)
                .withPropertyValues("leave.approval.enabled=true", "leave.approval.service-id=approval-service",
                        "leave.approval.http-contract-verified=true", "leave.approval.results.enabled=true",
                        "leave.approval.results.contract-verified=true", "spring.kafka.bootstrap-servers=unused:9092")
                .run(context -> {
                    assertThat(context.getBean(ApprovalClient.class)).isInstanceOf(ApprovalHttpClient.class);
                    org.mockito.Mockito.verify(factory).create(null, "approval-service", Duration.ofSeconds(2), Duration.ofSeconds(3));
                });
    }

    @Test
    void unavailableCreateAndLookupDoNotInventSuccess() {
        ApprovalClient client = new UnavailableApprovalClient();
        assertThatThrownBy(() -> client.create(null)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.APPROVAL_INTEGRATION_UNAVAILABLE));
        assertThatThrownBy(() -> client.findByRequestId(UUID.randomUUID())).isInstanceOf(BusinessException.class);
    }
}
