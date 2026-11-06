package com.ssafy.attendanceservice.global.config;

import com.ssafy.nexa.security.NexaSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 토큰 검증과 401/403 응답은 nexa-security 공통 설정을 쓴다 (Authorization: Bearer 의 access 토큰만 받음).
 * 여기서는 근태 서비스의 경로별 권한만 정한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, NexaSecurityConfigurer nexa) throws Exception {
        return nexa.apply(http)
                .authorizeHttpRequests(auth -> auth
                        // 근태 현황·직원 타임라인 조회는 팀장급 이상이 쓰므로 로그인만 확인하고,
                        // 볼 수 있는 범위는 서비스(AttendanceViewScope)가 정한다. 근무 기록 등록·수정·삭제는 관리자만
                        .requestMatchers("/api/v1/attn/admin/daily", "/api/v1/attn/admin/period").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/attn/admin/employees/*/daily/*").authenticated()
                        .requestMatchers("/api/v1/attn/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .build();
    }
}
