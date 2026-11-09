package com.ssafy.leaveservice.global.config;

import com.ssafy.leaveservice.client.attendance.AttendanceHolidayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(AttendanceHolidayProperties.class)
public class LeavePreviewConfiguration {
    @Bean
    public Clock leavePreviewClock() {
        return Clock.systemUTC();
    }
}
