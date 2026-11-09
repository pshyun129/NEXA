
package com.ssafy.gatewayservice.config;

import com.ssafy.gatewayservice.exception.GatewayErrorWriter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Configuration
public class SecurityConfig {

    // 로그인 / 로그아웃 / 재발급: Access 인증 없이 통과
    @Bean
    @Order(1)
    public SecurityWebFilterChain publicAuthChain(
            ServerHttpSecurity http,
            CorsConfigurationSource corsSource
    ) {
        return http
                .securityMatcher(
                        ServerWebExchangeMatchers.matchers(
                                ServerWebExchangeMatchers.pathMatchers(
                                        HttpMethod.GET,
                                        "/actuator/health"
                                ),
                                ServerWebExchangeMatchers.pathMatchers(
                                        HttpMethod.POST,
                                        "/api/v1/auth/login",
                                        "/api/v1/auth/logout",
                                        "/api/v1/auth/reissue"
                                )
                        )
                )
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsSource))
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .anyExchange().permitAll()
                )
                .build();
    }

    // 일반 API: Access Cookie 기반 JWT 인증
    @Bean
    @Order(2)
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            CorsConfigurationSource corsSource,
            GatewayErrorWriter errorWriter
    ) {

        // accessToken Cookie -> 인증 토큰
        ServerAuthenticationConverter cookieConverter = exchange -> {

            // CORS preflight는 JWT 인증 시도하지 않음
            if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
                return Mono.empty();
            }

            HttpCookie cookie = exchange.getRequest()
                    .getCookies()
                    .getFirst("accessToken");

            if (cookie == null || cookie.getValue().isBlank()) {
                return Mono.empty();
            }

            return Mono.just(
                    new BearerTokenAuthenticationToken(cookie.getValue())
            );
        };

        // JWT의 role -> Spring Security ROLE_* 권한
        ReactiveJwtAuthenticationConverter jwtAuthConverter =
                new ReactiveJwtAuthenticationConverter();

        jwtAuthConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");

            if ("ADMIN".equals(role) || "USER".equals(role)) {
                return Flux.<GrantedAuthority>just(
                        new SimpleGrantedAuthority("ROLE_" + role)
                );
            }

            return Flux.empty();
        });

        // 기존 GatewayErrorWriter 재사용
        ServerAuthenticationEntryPoint authenticationEntryPoint =
                (exchange, ex) -> errorWriter.write(
                        exchange,
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED",
                        "인증이 필요하거나 토큰이 유효하지 않습니다."
                );

        ServerAccessDeniedHandler accessDeniedHandler =
                (exchange, ex) -> errorWriter.write(
                        exchange,
                        HttpStatus.FORBIDDEN,
                        "FORBIDDEN",
                        "접근 권한이 없습니다."
                );

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsSource))
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)

                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyExchange().authenticated()
                )

                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )

                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenConverter(cookieConverter)
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthConverter)
                        )
                )

                .build();
    }
}
