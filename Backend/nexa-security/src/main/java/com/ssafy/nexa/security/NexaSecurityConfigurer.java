package com.ssafy.nexa.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * 서비스 공통 보안 설정을 HttpSecurity 에 적용한다. 경로별 권한 규칙은 각 서비스가 이어서 작성한다.
 * <pre>{@code
 * @Bean
 * SecurityFilterChain filterChain(HttpSecurity http, NexaSecurityConfigurer nexa) throws Exception {
 *     return nexa.apply(http)
 *             .authorizeHttpRequests(auth -> auth
 *                     .requestMatchers("/api/v1/xxx/admin/**").hasRole("ADMIN")
 *                     .anyRequest().authenticated())
 *             .build();
 * }
 * }</pre>
 */
public class NexaSecurityConfigurer {

    private final NexaSecurityProperties properties;
    private final NexaAuthenticationEntryPoint authenticationEntryPoint;
    private final NexaAccessDeniedHandler accessDeniedHandler;

    public NexaSecurityConfigurer(NexaSecurityProperties properties,
                                  NexaAuthenticationEntryPoint authenticationEntryPoint,
                                  NexaAccessDeniedHandler accessDeniedHandler) {
        this.properties = properties;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /** 세션 없이 Authorization: Bearer 의 access 토큰으로만 인증한다. permit-urls 는 토큰 없이 연다 */
    public HttpSecurity apply(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(properties.permitUrls().toArray(String[]::new)).permitAll())
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(authenticationEntryPoint) // 토큰이 틀렸을 때
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(j -> j.jwtAuthenticationConverter(roleConverter())))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint) // 토큰이 없을 때
                        .accessDeniedHandler(accessDeniedHandler));         // 토큰은 맞는데 권한이 부족할 때
    }

    /** role 클레임(USER/ADMIN)을 ROLE_USER/ROLE_ADMIN 권한으로 바꾼다 (hasRole 로 검사) */
    static JwtAuthenticationConverter roleConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
