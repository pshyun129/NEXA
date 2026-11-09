package com.ssafy.nexa.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * nexa.security.* 설정.
 *
 * @param jwksUri    access 토큰 서명 확인용 공개키 주소 (user-service JWKS). 처음 토큰을 검증할 때 받아온다
 * @param permitUrls 토큰 없이 열어 둘 경로
 */
@ConfigurationProperties("nexa.security")
public record NexaSecurityProperties(
        @DefaultValue("http://localhost:8081/.well-known/jwks.json") String jwksUri,
        @DefaultValue({"/actuator/health", "/error"}) List<String> permitUrls
) {
}
