package com.ssafy.leaveservice.client.hr;

import com.ssafy.leaveservice.global.config.LeaveHrConfiguration;
import com.ssafy.leaveservice.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class HrConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(LeaveHrConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings={"dev","prod","production","test,prod","test,production"})
    void onlyUnavailableClientIsPackagedForEveryRuntimeProfile(String profiles) {
        runner.withInitializer(context->context.getEnvironment().setActiveProfiles(profiles.split(",")))
                .withPropertyValues("leave.hr.enabled=true").run(context->{
                    assertThat(context).hasSingleBean(HrClient.class);
                    assertThat(context.getBean(HrClient.class)).isInstanceOf(UnavailableHrClient.class);
                    assertThat(context.getBean(HrClient.class).isAvailable()).isFalse();
                    assertThat(context.getBean(HrIntegrationProperties.class).hasConfiguredEndpoints()).isFalse();
                    assertThat(context.getBean(HrIntegrationProperties.class).hasConfiguredClaims()).isFalse();
                });
    }

    @Test
    void stubNeverInventsEmployeeMembersOrDuty() {
        HrClient client = new UnavailableHrClient();
        assertThatThrownBy(()->client.findEmployee(10L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->client.findDepartmentMembers(10L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->client.findDuty(7L)).isInstanceOf(BusinessException.class);
    }
}
