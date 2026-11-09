package com.ssafy.leaveservice.client.attendance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("leave.attendance.revert")
public record AttendanceRevertProperties(boolean enabled, boolean contractVerified, boolean idempotencyVerified) {}
