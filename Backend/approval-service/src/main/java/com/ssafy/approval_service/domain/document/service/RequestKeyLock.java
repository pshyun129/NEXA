package com.ssafy.approval_service.domain.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 같은 요청 키의 문서 생성을 한 번에 하나씩 처리하기 위한 PostgreSQL advisory lock.
 * 아직 행이 없는 키라 행 잠금을 쓸 수 없으므로 키 값으로 잠근다. 트랜잭션이 끝나면 자동으로 풀린다.
 * UUID 를 64비트로 줄이므로 서로 다른 키가 같은 잠금을 쓸 수 있지만, 그 경우 잠깐 기다릴 뿐 결과는 같다.
 */
@Component
@RequiredArgsConstructor
public class RequestKeyLock {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID requestKey) {
        long key = requestKey.getMostSignificantBits() ^ requestKey.getLeastSignificantBits();
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)", rs -> null, key);
    }
}
