package com.ssafy.leaveservice.client.attendance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("leave.attendance")
public record AttendanceHolidayProperties(
        String baseUrl,
        String serviceId,
        Duration connectTimeout,
        Duration readTimeout
) {}
