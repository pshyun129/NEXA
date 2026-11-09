package com.ssafy.user_service.global.security.jwt;

import com.ssafy.user_service.global.security.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private static final String ESCAPED_NEWLINE = String.valueOf((char) 92) + "n";

    static JwtProperties props(String privatePem, String publicPem) {
        return new JwtProperties(privatePem, publicPem, 900_000L, 604_800_000L);
    }

    static JwtKeyProvider provider(JwtProperties props) throws Exception {
        JwtKeyProvider p = new JwtKeyProvider(props);
        p.init();
        return p;
    }

    static JwtUtil jwtUtil(JwtProperties props) throws Exception {
        return new JwtUtil(provider(props), props);
    }

    static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    // 환경변수로 주입될 때처럼 줄바꿈이 백슬래시+n 리터럴인 PEM
    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----" + ESCAPED_NEWLINE
                + Base64.getEncoder().encodeToString(der)
                + ESCAPED_NEWLINE + "-----END " + type + "-----";
    }

    @Test
    void 임시키로_발급한_토큰을_검증하고_claim을_읽는다() throws Exception {
        JwtUtil util = jwtUtil(props("", ""));

        IssuedToken access = util.generateAccessToken(7L, Role.ADMIN, null, null, null);
        IssuedToken refresh = util.generateRefreshToken(7L);

        Claims ac = util.parseClaims(access.value());
        assertEquals(7L, util.getUserId(ac));
        assertEquals(JwtUtil.TYPE_ACCESS, util.getType(ac));
        assertEquals(Role.ADMIN, util.getRole(ac));
        assertEquals(access.id(), util.getTokenId(ac));
        assertTrue(util.getRemainExpiration(ac) > 0);

        Claims rc = util.parseClaims(refresh.value());
        assertEquals(JwtUtil.TYPE_REFRESH, util.getType(rc));
        assertNull(util.getRole(rc));
        assertNotEquals(access.id(), refresh.id());
    }

    @Test
    void access_토큰에_소속_ID를_싣고_읽는다() throws Exception {
        JwtUtil util = jwtUtil(props("", ""));

        Claims withJob = util.parseClaims(util.generateAccessToken(7L, Role.USER, 12L, 3L, 5L).value());
        assertEquals(12L, util.getDeptId(withJob));
        assertEquals(3L, util.getGradeId(withJob));
        assertEquals(5L, util.getDutyId(withJob));

        // 값이 없으면 claim 자체를 넣지 않는다
        Claims withoutJob = util.parseClaims(util.generateAccessToken(7L, Role.USER, null, null, null).value());
        assertFalse(withoutJob.containsKey("deptId"));
        assertNull(util.getDeptId(withoutJob));
        assertNull(util.getGradeId(withoutJob));
        assertNull(util.getDutyId(withoutJob));

        // refresh 토큰에는 소속 정보를 싣지 않는다
        Claims refresh = util.parseClaims(util.generateRefreshToken(7L).value());
        assertNull(util.getDeptId(refresh));
    }

    @Test
    void PEM_개인키를_읽고_공개키를_유도한다() throws Exception {
        KeyPair pair = newKeyPair();
        JwtKeyProvider p = provider(props(pem("PRIVATE KEY", pair.getPrivate().getEncoded()), ""));
        assertArrayEquals(pair.getPublic().getEncoded(), p.getPublicKey().getEncoded());
        assertEquals(16, p.getKeyId().length());
    }

    @Test
    void 공개키가_개인키와_쌍이_아니면_기동_실패() throws Exception {
        KeyPair a = newKeyPair();
        KeyPair b = newKeyPair();
        JwtProperties mismatched = props(pem("PRIVATE KEY", a.getPrivate().getEncoded()),
                pem("PUBLIC KEY", b.getPublic().getEncoded()));
        assertThrows(IllegalStateException.class, () -> provider(mismatched));

        JwtProperties matched = props(pem("PRIVATE KEY", a.getPrivate().getEncoded()),
                pem("PUBLIC KEY", a.getPublic().getEncoded()));
        assertDoesNotThrow(() -> provider(matched));
    }

    @Test
    void 다른_키로_서명한_토큰은_거부된다() throws Exception {
        JwtUtil signer = jwtUtil(props("", ""));
        JwtUtil verifier = jwtUtil(props("", ""));
        String token = signer.generateAccessToken(1L, Role.USER, null, null, null).value();
        assertThrows(JwtException.class, () -> verifier.parseClaims(token));
    }

    @Test
    void 만료된_토큰도_서명이_맞으면_claims를_읽을_수_있다() throws Exception {
        JwtProperties props = new JwtProperties("", "", -1_000L, 604_800_000L); // 이미 만료된 access
        JwtUtil util = jwtUtil(props);
        String expired = util.generateAccessToken(3L, Role.USER, null, null, null).value();
        assertThrows(JwtException.class, () -> util.parseClaims(expired));
        assertEquals(3L, util.getUserId(util.parseClaimsAllowExpired(expired)));
    }
}
