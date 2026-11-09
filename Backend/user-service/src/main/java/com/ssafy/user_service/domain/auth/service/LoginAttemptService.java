package com.ssafy.user_service.domain.auth.service;

import com.ssafy.user_service.global.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 로그인 실패 횟수 제한 (Redis).
 * LF:{loginId} 실패 횟수(failureWindow 동안 유지), LK:{loginId} 잠금 표시(lockDuration 동안 유지).
 * 계정 존재 여부와 무관하게 loginId 기준으로 세어, 응답으로 계정 존재가 드러나지 않게 한다
 * (남은 시도 횟수·잠금 남은 시간도 같은 기준이라 계정이 없어도 똑같이 나간다).
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final String FAIL_PREFIX = "LF:";
    private static final String LOCK_PREFIX = "LK:";
    /** Redis getExpire: 키 없음 */
    private static final long KEY_MISSING = -2L;
    /** Redis getExpire: 만료 없음 (잠금 키는 항상 만료를 두므로 생기면 안 되지만, 생기면 설정된 잠금 시간으로 본다) */
    private static final long NO_EXPIRE = -1L;

    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;

    /** 잠겨 있으면 해제까지 남은 시간(1초 이상), 아니면 empty */
    public Optional<Duration> lockRemaining(String loginId) {
        Long seconds = redisTemplate.getExpire(LOCK_PREFIX + loginId, TimeUnit.SECONDS);
        if (seconds == null || seconds == KEY_MISSING) {
            return Optional.empty();
        }
        if (seconds == NO_EXPIRE) {
            return Optional.of(appProperties.login().lockDuration());
        }
        // TTL 이 1초 미만이면 0 으로 내려오는데, 키가 아직 있으므로 잠금으로 본다
        return Optional.of(Duration.ofSeconds(Math.max(seconds, 1L)));
    }

    /**
     * 실패를 기록하고, 한도에 도달하면 잠금.
     * @return 잠기기 전까지 남은 시도 횟수. 이번 실패로 잠겼으면 0
     */
    public int recordFailure(String loginId) {
        AppProperties.Login login = appProperties.login();
        String failKey = FAIL_PREFIX + loginId;
        Long failures = redisTemplate.opsForValue().increment(failKey);
        if (failures != null && failures == 1L) {
            redisTemplate.expire(failKey, login.failureWindow());
        }
        if (failures != null && failures >= login.maxFailures()) {
            redisTemplate.opsForValue().set(LOCK_PREFIX + loginId, "locked", login.lockDuration());
            redisTemplate.delete(failKey);
            return 0;
        }
        long used = failures == null ? 0 : failures;
        return (int) Math.max(login.maxFailures() - used, 0);
    }

    public void reset(String loginId) {
        redisTemplate.delete(FAIL_PREFIX + loginId);
    }
}
