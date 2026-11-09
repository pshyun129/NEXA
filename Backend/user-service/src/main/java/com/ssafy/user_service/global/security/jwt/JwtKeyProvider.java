package com.ssafy.user_service.global.security.jwt;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RS256 서명 키를 제공한다.
 * - jwt.private-key (PKCS#8 PEM) 가 있으면 그 키를 사용하고, 공개키는 jwt.public-key 또는 개인키에서 유도한다.
 *   둘 다 주어지면 쌍이 맞는지 기동 시 검증한다.
 * - 비어 있으면 기동 시 임시 키쌍을 생성한다. 로컬 개발 전용이며 재시작 시 기존 토큰이 모두 무효가 되고
 *   다른 서비스가 JWKS로 검증할 수 없으므로 운영에서는 반드시 키를 주입해야 한다.
 * 생성: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtKeyProvider {

    // 환경변수로 들어온 PEM은 줄바꿈이 백슬래시+n 리터럴일 수 있다
    private static final String ESCAPED_NEWLINE = String.valueOf((char) 92) + "n";

    private final JwtProperties properties;

    @Getter private RSAPrivateKey privateKey;
    @Getter private RSAPublicKey publicKey;
    @Getter private String keyId;

    @PostConstruct
    public void init() throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        String privatePem = properties.privateKey();
        String publicPem = properties.publicKey();

        if (privatePem == null || privatePem.isBlank()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            privateKey = (RSAPrivateKey) pair.getPrivate();
            publicKey = (RSAPublicKey) pair.getPublic();
            log.warn("jwt.private-key 가 설정되지 않아 임시 RSA 키를 생성했습니다. 로컬 개발 외에는 사용하지 마세요.");
        } else {
            privateKey = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decodePem(privatePem)));
            RSAPublicKey derived = derivePublicKey(keyFactory, privateKey);
            if (publicPem != null && !publicPem.isBlank()) {
                publicKey = (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(decodePem(publicPem)));
                if (derived != null && (!derived.getModulus().equals(publicKey.getModulus())
                        || !derived.getPublicExponent().equals(publicKey.getPublicExponent()))) {
                    throw new IllegalStateException("jwt.public-key 가 jwt.private-key 와 쌍이 아닙니다.");
                }
            } else if (derived != null) {
                publicKey = derived;
            } else {
                throw new IllegalStateException("공개키를 유도할 수 없습니다. jwt.public-key 를 설정하세요.");
            }
        }
        keyId = thumbprint(publicKey);
    }

    private static RSAPublicKey derivePublicKey(KeyFactory keyFactory, RSAPrivateKey privateKey) throws Exception {
        if (privateKey instanceof RSAPrivateCrtKey crt) {
            return (RSAPublicKey) keyFactory.generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        }
        return null;
    }

    // 공개키 DER의 SHA-256 앞 16자를 kid로 사용 (키 교체 시 JWKS에서 구분)
    private static String thumbprint(RSAPublicKey key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
    }

    private static byte[] decodePem(String pem) {
        String body = pem.replace(ESCAPED_NEWLINE, "\n")
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("[ \t\r\n]", "");
        return Base64.getDecoder().decode(body);
    }
}
