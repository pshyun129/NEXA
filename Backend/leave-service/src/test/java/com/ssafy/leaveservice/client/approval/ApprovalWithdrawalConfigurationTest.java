package com.ssafy.leaveservice.client.approval;

import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalClient;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalProperties;
import com.ssafy.leaveservice.client.approval.withdrawal.UnavailableApprovalWithdrawalClient;
import com.ssafy.leaveservice.global.config.LeaveApprovalWithdrawalConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalWithdrawalConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FakeApprovalWithdrawalClientConfiguration.class, LeaveApprovalWithdrawalConfiguration.class);

    @Test
    void disabledByDefaultAndUnavailableEvenWhenEnabledWithoutHttpImplementation() {
        runner.run(context -> {
            assertThat(context.getBean(ApprovalWithdrawalProperties.class).enabled()).isFalse();
            assertThat(context.getBean(ApprovalWithdrawalClient.class)).isInstanceOf(UnavailableApprovalWithdrawalClient.class);
            assertThat(context.getBean(ApprovalWithdrawalClient.class).isAvailable()).isFalse();
        });
        runner.withPropertyValues("leave.approval.withdrawal.enabled=true").run(context ->
                assertThat(context.getBean(ApprovalWithdrawalClient.class).isAvailable()).isFalse());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "test,prod", "test,production", "dev,production"})
    void productionCannotRegisterTestFake(String profiles) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles.split(",")))
                .withPropertyValues("leave.approval.withdrawal.enabled=true").run(context -> {
                    assertThat(context).hasSingleBean(ApprovalWithdrawalClient.class);
                    assertThat(context).doesNotHaveBean(FakeApprovalWithdrawalClientConfiguration.FakeApprovalWithdrawalClient.class);
                    assertThat(context.getBean(ApprovalWithdrawalClient.class).isAvailable()).isFalse();
                });
    }

    @Test
    void explicitTestProfileMayRegisterFake() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test")).run(context ->
                assertThat(context.getBean(ApprovalWithdrawalClient.class))
                        .isInstanceOf(FakeApprovalWithdrawalClientConfiguration.FakeApprovalWithdrawalClient.class));
    }

    @Test
    void unavailableClientCannotInventWithdrawalOrLookupSuccess() {
        ApprovalWithdrawalClient client = new UnavailableApprovalWithdrawalClient();
        assertThatThrownBy(() -> client.withdraw(null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> client.findByRequestId(UUID.randomUUID())).isInstanceOf(RuntimeException.class);
    }
}
