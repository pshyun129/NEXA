package com.ssafy.migrationservice.global.config;

import com.ssafy.nexa.security.NexaSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 토큰 검증과 401/403 응답은 nexa-security 공통 설정을 쓴다 (Authorization: Bearer 의 access 토큰만 받음).
 * 이관 API는 회사 전체 인사 데이터를 다루므로 관리자만 쓴다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, NexaSecurityConfigurer nexa) throws Exception {
        return nexa.apply(http)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/migrations/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .build();
    }
}
