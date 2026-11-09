package com.ssafy.leaveservice.client.hr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

@ConfigurationProperties("leave.hr")
public record HrIntegrationProperties(
        boolean enabled,
        String baseUrl,
        String serviceId,
        String employeeInfoUri,
        String departmentMembersUri,
        String dutyInfoUri,
        Duration connectTimeout,
        Duration readTimeout,
        JwtClaims jwtClaims
) {
    public boolean hasConfiguredEndpoints() {
        return Stream.of(employeeInfoUri, departmentMembersUri, dutyInfoUri)
                .allMatch(value -> value != null && !value.isBlank());
    }

    public boolean hasConfiguredClaims() {
        if (jwtClaims == null) {
            return false;
        }
        List<String> reserved = List.of("sub", "role", "type", "iss", "aud", "iat", "exp", "nbf", "jti");
        List<String> keys = Stream.of(jwtClaims.departmentId(), jwtClaims.dutyId()).toList();
        return keys.stream().allMatch(key -> key != null && !key.isBlank() && !reserved.contains(key))
                && keys.stream().distinct().count() == 2;
    }

    // User Service access 토큰의 deptId와 dutyId 키를 설정한다.
    public record JwtClaims(String departmentId, String dutyId) {}
}
