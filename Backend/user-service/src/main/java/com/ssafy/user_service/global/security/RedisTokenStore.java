package com.ssafy.user_service.global.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedisTokenStore implements TokenStore {

    private static final String REFRESH_PREFIX = "RT:";   // RT:{employeeId} -> refresh jti
    // BL: 접두사는 gateway-service JwtConfig 의 블랙리스트 확인("BL:" + jti)과 같아야 한다.
    // 한쪽만 바꾸면 에러 없이 로그아웃 토큰이 gateway 를 통과하므로 반드시 함께 바꾼다
    private static final String BLACKLIST_PREFIX = "BL:"; // BL:{access jti} -> "logout"

    // GET-비교-SET 을 Redis 안에서 한 번에 수행. 동시 재발급 중 하나만 성공하고,
    // 다른 값이 저장돼 있으면(이미 회전된 토큰 재사용) 키를 지워 세션을 끊는다.
    private static final RedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            local cur = redis.call('GET', KEYS[1])
            if cur == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
                return 1
            end
            if cur then
                redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void saveRefreshToken(Long employeeId, String tokenId, long ttlMillis) {
        if (ttlMillis <= 0) return;
        redisTemplate.opsForValue()
                .set(REFRESH_PREFIX + employeeId, tokenId, ttlMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean rotateRefreshToken(Long employeeId, String expectedId, String newId, long ttlMillis) {
        if (ttlMillis <= 0) return false;
        Long result = redisTemplate.execute(ROTATE_SCRIPT,
                List.of(REFRESH_PREFIX + employeeId),
                expectedId, newId, String.valueOf(ttlMillis));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public void deleteRefreshToken(Long employeeId) {
        redisTemplate.delete(REFRESH_PREFIX + employeeId);
    }

    @Override
    public void blacklistAccessToken(String tokenId, long ttlMillis) {
        if (ttlMillis <= 0) return; // 이미 만료된 토큰은 블랙리스트가 필요 없다
        redisTemplate.opsForValue()
                .set(BLACKLIST_PREFIX + tokenId, "logout", ttlMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isBlacklisted(String tokenId) {
        return tokenId != null && Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + tokenId));
    }
}
