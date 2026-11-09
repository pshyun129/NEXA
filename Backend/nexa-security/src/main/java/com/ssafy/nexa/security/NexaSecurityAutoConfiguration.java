package com.ssafy.nexa.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * 의존성만 추가하면 등록되는 공통 보안 빈. 서비스가 같은 타입의 빈을 직접 만들면 그쪽을 쓴다.
 * SecurityFilterChain 은 만들지 않는다 (경로별 권한은 서비스마다 다르므로 NexaSecurityConfigurer 로 조립).
 * Boot 기본 설정보다 먼저 등록해 JwtDecoder 가 이쪽 것으로 정해지게 한다
 * (기본 JwtDecoder 와, JwtDecoder 가 없을 때 생기는 임시 로그인 계정이 만들어지지 않는다).
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(NexaSecurityProperties.class)
public class NexaSecurityAutoConfiguration {

    /** user-service JWKS 공개키(RS256)로 서명을 확인하고 access 토큰 규칙을 검사한다 */
    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(NexaSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(NexaJwtValidators.accessToken());
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    NexaAuthenticationEntryPoint nexaAuthenticationEntryPoint() {
        return new NexaAuthenticationEntryPoint();
    }

    @Bean
    @ConditionalOnMissingBean
    NexaAccessDeniedHandler nexaAccessDeniedHandler() {
        return new NexaAccessDeniedHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    NexaSecurityConfigurer nexaSecurityConfigurer(NexaSecurityProperties properties,
                                                  NexaAuthenticationEntryPoint authenticationEntryPoint,
                                                  NexaAccessDeniedHandler accessDeniedHandler) {
        return new NexaSecurityConfigurer(properties, authenticationEntryPoint, accessDeniedHandler);
    }

    @Bean
    @ConditionalOnMissingBean
    BearerTokenRelayInterceptor bearerTokenRelayInterceptor() {
        return new BearerTokenRelayInterceptor();
    }
}
