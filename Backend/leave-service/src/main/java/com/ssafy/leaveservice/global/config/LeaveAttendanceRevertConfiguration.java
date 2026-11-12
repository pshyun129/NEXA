package com.ssafy.leaveservice.global.config;

import com.ssafy.leaveservice.client.LeaveRestClientFactory;
import com.ssafy.leaveservice.client.attendance.AttendanceHolidayProperties;
import com.ssafy.leaveservice.client.attendance.AttendanceRevertClient;
import com.ssafy.leaveservice.client.attendance.AttendanceRevertCommand;
import com.ssafy.leaveservice.client.attendance.AttendanceRevertResult;
import com.ssafy.leaveservice.client.attendance.AttendanceRevertHttpClient;
import com.ssafy.leaveservice.client.attendance.AttendanceRevertProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AttendanceRevertProperties.class, AttendanceHolidayProperties.class})
public class LeaveAttendanceRevertConfiguration {
    @Bean
    @ConditionalOnMissingBean(AttendanceRevertClient.class)
    public AttendanceRevertClient attendanceRevertClient(
            AttendanceRevertProperties properties,
            AttendanceHolidayProperties attendance,
            ObjectProvider<LeaveRestClientFactory> factory
    ) {
        if (!properties.enabled() || !properties.contractVerified()) {
            return new AttendanceRevertClient() {
                public boolean isAvailable() { return false; }
                public AttendanceRevertResult revert(AttendanceRevertCommand command) {
                    return AttendanceRevertResult.unknown(command);
                }
            };
        }
        return new AttendanceRevertHttpClient(factory.getObject().createWithoutBearerRelay(attendance.baseUrl(), attendance.serviceId(),
                attendance.connectTimeout(), attendance.readTimeout()), properties.idempotencyVerified());
    }
}
