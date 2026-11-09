package com.ssafy.user_service.global.config;

import com.ssafy.user_service.global.security.JwtAccessDeniedHandler;
import com.ssafy.user_service.global.security.JwtAuthenticationEntryPoint;
import com.ssafy.user_service.global.security.JwtFilter;
import com.ssafy.user_service.global.security.SecurityConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final AppProperties appProperties;

    // 관리자 전용 경로 (Employee.role == ADMIN, access 토큰의 role claim으로 판별)
    private static final String[] ADMIN_URLS = {
            SecurityConstants.ADMIN_BASE_PATH + "/**"
    };

    // 토큰 없이 접근 가능한 경로 (AuthController 와 일치해야 함)
    private static final String[] PERMIT_URLS = {
            SecurityConstants.AUTH_BASE_PATH + "/login",
            SecurityConstants.AUTH_BASE_PATH + "/logout",   // access 만료 후에도 세션 정리가 가능해야 함
            SecurityConstants.AUTH_BASE_PATH + "/reissue",
            SecurityConstants.JWKS_PATH,
            // migration-service 는 토큰 없이 호출한다. gateway 에 /internal 라우트가 없어 내부망에서만 닿는다
            // TODO 서비스 간 인증(공유 토큰·mTLS 등)은 별도 과제
            SecurityConstants.INTERNAL_MIGRATION_PATH + "/**",
            "/actuator/health",
            "/error",
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // 인증은 쿠키의 JWT로 처리하며 서버 세션은 사용하지 않음.
        // 쿠키 SameSite=Lax 전제에서 CSRF를 끈 것이므로, None으로 바꾸면 CSRF 방어를 다시 켜야 한다
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PERMIT_URLS).permitAll()
                        .requestMatchers(ADMIN_URLS).hasRole("ADMIN")
                        // 사원 API는 조회만 인증 사용자에게 열고, 등록/퇴직 처리 등 쓰기는 관리자 전용
                        .requestMatchers(HttpMethod.POST, SecurityConstants.EMPLOYEE_BASE_PATH + "/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, SecurityConstants.EMPLOYEE_BASE_PATH + "/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint) // 토큰이 없거나 틀렸을 때
                        .accessDeniedHandler(jwtAccessDeniedHandler)); // 토큰은 맞는데 권한이 부족할 때
        return http.build();
    }

    // 쿠키 인증(allowCredentials)과 함께 쓰므로 와일드카드 없이 정확한 origin만 허용
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(appProperties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
