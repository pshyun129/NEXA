package com.ssafy.user_service.global.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.properties 의 jwt.* 설정
 * @param privateKey        RS256 서명용 PKCS#8 PEM. 비어 있으면 기동 시 임시 키 생성(로컬 전용)
 * @param publicKey         X.509 PEM. 비어 있으면 개인키에서 유도
 * @param accessExpiration  access 토큰 수명(ms)
 * @param refreshExpiration refresh 토큰 수명(ms)
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String privateKey, String publicKey, long accessExpiration, long refreshExpiration) {
}
