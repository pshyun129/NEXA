package com.ssafy.approval_service.global.security;

import com.ssafy.nexa.security.NexaSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 토큰 검증과 401/403 응답은 nexa-security 공통 설정을 쓴다 (Authorization: Bearer 의 access 토큰만 받음).
 * 여기서는 결재 서비스의 경로별 권한만 정한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, NexaSecurityConfigurer nexa) throws Exception {
        return nexa.apply(http)
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated())
                .build();
    }
}
