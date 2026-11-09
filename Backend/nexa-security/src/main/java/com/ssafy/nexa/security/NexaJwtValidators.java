package com.ssafy.nexa.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Duration;

/** user-service 가 발급한 access 토큰 검증 규칙 (서명 확인 뒤에 적용) */
public final class NexaJwtValidators {

    private NexaJwtValidators() {
    }

    /** 만료(허용 오차 없음), type=access, sub 는 양수 사원 ID */
    public static OAuth2TokenValidator<Jwt> accessToken() {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                NexaJwtValidators::validateAccessToken);
    }

    private static OAuth2TokenValidatorResult validateAccessToken(Jwt jwt) {
        if (!"access".equals(jwt.getClaimAsString("type"))) {
            return invalid("access 토큰이 아닙니다.");
        }
        try {
            if (jwt.getSubject() == null || Long.parseLong(jwt.getSubject()) <= 0) {
                return invalid("사원 ID가 올바르지 않습니다.");
            }
        } catch (NumberFormatException e) {
            return invalid("사원 ID가 올바르지 않습니다.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
