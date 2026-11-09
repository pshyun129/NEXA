package com.ssafy.user_service.global.security.jwt;

/** 발급된 토큰 원문과 jti, 만료 시각(epoch ms) */
public record IssuedToken(String value, String id, long expiresAt) {

    public long remainingMillis() {
        return expiresAt - System.currentTimeMillis();
    }
}
