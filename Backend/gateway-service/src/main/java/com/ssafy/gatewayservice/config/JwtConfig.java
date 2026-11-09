
package com.ssafy.gatewayservice.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import reactor.core.publisher.Mono;

@Configuration
public class JwtConfig {

    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
            String jwksUri,
            ReactiveStringRedisTemplate redisTemplate
    ) {

        // User Service의 공개키로 RS256 서명 검증
        NimbusReactiveJwtDecoder decoder =
                NimbusReactiveJwtDecoder
                        .withJwkSetUri(jwksUri)
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .build();

        // User Service에서 발급하는 Access Token 규격 검증
        OAuth2TokenValidator<Jwt> accessValidator = jwt -> {

            if (!"access".equals(jwt.getClaimAsString("type"))) {
                return invalid("Access Token이 아닙니다.");
            }

            String subject = jwt.getSubject();

            try {
                if (subject == null || Long.parseLong(subject) <= 0) {
                    return invalid("직원 ID가 올바르지 않습니다.");
                }
            } catch (NumberFormatException ex) {
                return invalid("직원 ID가 올바르지 않습니다.");
            }

            if (jwt.getId() == null || jwt.getId().isBlank()) {
                return invalid("JWT ID가 없습니다.");
            }

            if (jwt.getExpiresAt() == null) {
                return invalid("JWT 만료 시간이 없습니다.");
            }

            return OAuth2TokenValidatorResult.success();
        };

        // 만료 허용 오차 0초 + Access Token 규격 검증
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(Duration.ZERO),
                        accessValidator
                )
        );

        // 서명 및 클레임 검증 성공 후 Redis 블랙리스트 검사.
        // 키 규칙(BL:{access jti})은 user-service RedisTokenStore.BLACKLIST_PREFIX 와 같아야 한다
        return token -> decoder.decode(token)
                .flatMap(jwt ->
                        redisTemplate.hasKey("BL:" + jwt.getId())
                                .flatMap(blocked -> {
                                    if (Boolean.TRUE.equals(blocked)) {
                                        return Mono.<Jwt>error(
                                                new BadJwtException(
                                                        "로그아웃된 Access Token입니다."
                                                )
                                        );
                                    }
                                    return Mono.just(jwt);
                                })
                                .switchIfEmpty(Mono.error(
                                        new IllegalStateException(
                                                "Redis 블랙리스트 조회 결과가 없습니다."
                                        )
                                ))
                );
    }

    private OAuth2TokenValidatorResult invalid(String message) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", message, null)
        );
    }
}
