package com.ssafy.user_service.global.security.jwt;

import com.ssafy.user_service.global.security.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtUtil {

    // access/refresh 토큰이 서로 대체 사용되지 않도록 구분하는 claim
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_ROLE = "role";
    // 소속 정보는 이름이 아닌 ID만 싣는다. 이름은 바뀔 수 있고 권한 판단에는 ID면 충분하다
    private static final String CLAIM_DEPT_ID = "deptId";
    private static final String CLAIM_GRADE_ID = "gradeId";
    private static final String CLAIM_DUTY_ID = "dutyId";

    private final JwtKeyProvider keyProvider;
    private final JwtProperties properties;

    // access 토큰에만 role/소속을 싣는다. refresh는 재발급 시 DB에서 다시 읽는다.
    // 소속 ID가 null이면 해당 claim은 넣지 않는다
    public IssuedToken generateAccessToken(Long userId, Role role, Long deptId, Long gradeId, Long dutyId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (role != null) extra.put(CLAIM_ROLE, role.name());
        if (deptId != null) extra.put(CLAIM_DEPT_ID, deptId);
        if (gradeId != null) extra.put(CLAIM_GRADE_ID, gradeId);
        if (dutyId != null) extra.put(CLAIM_DUTY_ID, dutyId);
        return generate(userId, TYPE_ACCESS, extra, properties.accessExpiration());
    }

    public IssuedToken generateRefreshToken(Long userId) {
        return generate(userId, TYPE_REFRESH, Map.of(), properties.refreshExpiration());
    }

    private IssuedToken generate(Long userId, String type, Map<String, Object> extra, long expiration) {
        String id = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + expiration;
        var builder = Jwts.builder()
                .header().keyId(keyProvider.getKeyId()).and()
                .id(id)
                .subject(userId.toString())
                .claim(CLAIM_TYPE, type)
                .issuedAt(new Date())
                .expiration(new Date(expiresAt));
        extra.forEach(builder::claim);
        String token = builder.signWith(keyProvider.getPrivateKey(), Jwts.SIG.RS256).compact();
        return new IssuedToken(token, id, expiresAt);
    }

    /**
     * 서명과 만료를 검증하고 claims를 반환한다.
     * 위변조/만료/형식 오류 시 {@link JwtException}(만료는 ExpiredJwtException)을 던진다.
     */
    public Claims parseClaims(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(keyProvider.getPublicKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 서명은 검증하되 만료는 허용하고 claims를 반환한다. 로그아웃처럼 만료된 토큰의 주체만 필요한 경우에 쓴다.
     * 위변조/형식 오류 시에는 여전히 JwtException을 던진다.
     */
    public Claims parseClaimsAllowExpired(String token) throws JwtException {
        try {
            return parseClaims(token);
        } catch (ExpiredJwtException e) {
            return e.getClaims(); // jjwt는 서명 검증 후에 만료를 검사하므로 claims를 신뢰할 수 있다
        }
    }

    public Long getUserId(Claims claims) {
        return Long.parseLong(claims.getSubject());
    }

    public String getType(Claims claims) {
        return claims.get(CLAIM_TYPE, String.class);
    }

    /** jti. 블랙리스트/refresh 저장 키로 사용 */
    public String getTokenId(Claims claims) {
        return claims.getId();
    }

    /** role claim이 없거나 알 수 없는 값이면 null */
    public Role getRole(Claims claims) {
        String role = claims.get(CLAIM_ROLE, String.class);
        if (role == null) return null;
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 부서 ID. claim이 없으면 null */
    public Long getDeptId(Claims claims) {
        return getLongClaim(claims, CLAIM_DEPT_ID);
    }

    /** 직급 ID. claim이 없으면 null */
    public Long getGradeId(Claims claims) {
        return getLongClaim(claims, CLAIM_GRADE_ID);
    }

    /** 직책 ID. claim이 없으면 null */
    public Long getDutyId(Claims claims) {
        return getLongClaim(claims, CLAIM_DUTY_ID);
    }

    // JSON 숫자는 크기에 따라 Integer/Long으로 역직렬화되므로 Number로 받아 변환한다
    private Long getLongClaim(Claims claims, String name) {
        Number value = claims.get(name, Number.class);
        return value == null ? null : value.longValue();
    }

    public long getRemainExpiration(Claims claims) {
        return claims.getExpiration().getTime() - System.currentTimeMillis();
    }
}
