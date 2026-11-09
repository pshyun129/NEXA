package com.ssafy.user_service.domain.auth.controller;

import com.ssafy.user_service.global.security.SecurityConstants;
import com.ssafy.user_service.global.security.jwt.JwtKeyProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 공개키 배포(JWKS). 게이트웨이와 approval-service 등 다른 서비스는 이 엔드포인트의 키로
 * user-service가 서명한 토큰을 검증한다. 개인키는 user-service 밖으로 나가지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtKeyProvider keyProvider;

    @GetMapping(SecurityConstants.JWKS_PATH)
    public Map<String, Object> jwks() {
        RSAPublicKey key = keyProvider.getPublicKey();
        // JWK(RFC 7517) 필드명. n/e 는 RSA 공개키의 modulus/exponent
        Map<String, Object> jwk = Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", keyProvider.getKeyId(),
                "n", base64Url(key.getModulus()),
                "e", base64Url(key.getPublicExponent())
        );
        return Map.of("keys", List.of(jwk));
    }

    // JWK는 부호 없는 big-endian 바이트를 base64url(패딩 없음)로 표현한다
    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
