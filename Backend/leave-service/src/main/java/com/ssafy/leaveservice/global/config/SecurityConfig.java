package com.ssafy.leaveservice.global.config;

import com.ssafy.nexa.security.NexaSecurityConfigurer;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess;
import com.ssafy.leaveservice.global.auth.LeaveAdministratorFilter;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 토큰 검증과 401/403 응답은 nexa-security 공통 설정을 쓴다 (Authorization: Bearer 의 access 토큰만 받음).
 * 여기서는 휴가 서비스의 경로별 권한만 정한다. 서비스의 관리자 확인(EmployeeAccess.isAdmin)도 그대로 둔다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            NexaSecurityConfigurer nexa,
            LeaveAdministratorAccess administratorAccess,
            EmployeeAccess employeeAccess,
            JsonMapper jsonMapper
    ) throws Exception {
        return nexa.apply(http)
                .addFilterAfter(new LeaveAdministratorFilter(administratorAccess, employeeAccess, jsonMapper),
                        ExceptionTranslationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // TODO 서비스 인증 계약 확정 후 이 GET의 익명 허용을 전용 호출 권한으로 교체한다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/leaves/internal/requests/{requestId}/dates").permitAll()
                        .requestMatchers("/api/v1/leaves/internal/**").denyAll()
                        .requestMatchers("/api/v1/leaves/admin/**").authenticated()
                        .anyRequest().authenticated())
                .build();
    }
}
