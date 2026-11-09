package com.ssafy.leaveservice.domain.adjustment.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;

@Repository
@RequiredArgsConstructor
public class LeaveAdjustmentRepository {

    private final JdbcTemplate jdbc;

    public void lockRequestKey(byte[] digest) {
        // 두 INT 키 공간은 기존 직원 잠금의 단일 BIGINT 키 공간과 겹치지 않는다.
        ByteBuffer lockKey = ByteBuffer.wrap(digest);
        jdbc.queryForList("select pg_advisory_xact_lock(?, ?)", lockKey.getInt(), lockKey.getInt());
    }

    public boolean lockOwnedAccount(Long employeeId, Long accountId) {
        return !jdbc.queryForList("""
                select account_id from leave_account where account_id = ? and emp_id = ? for update
                """, Long.class, accountId, employeeId).isEmpty();
    }

    public boolean lockOwnedGrant(Long accountId, Long grantId) {
        return !jdbc.queryForList("""
                select grant_id from leave_grant where account_id = ? and grant_id = ? for update
                """, Long.class, accountId, grantId).isEmpty();
    }

    public boolean hasEffectiveCredit(Long accountId, Long grantId, OffsetDateTime asOf) {
        // 역분개 원장 자체와 기준 시각까지 역분개된 지급은 최초 지급 근거로 사용하지 않는다.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from leave_ledger credit
                    where credit.account_id = ? and credit.grant_id = ? and credit.created_at <= ?
                      and credit.event_type in ('GRANT', 'CARRY_IN')
                      and credit.quantity_delta > 0 and credit.reserved_delta = 0
                      and credit.reverses_entry_id is null
                      and not exists (
                          select 1 from leave_ledger reversal
                          where reversal.reverses_entry_id = credit.entry_id and reversal.created_at <= ?
                      )
                )
                """, Boolean.class, accountId, grantId, asOf, asOf));
    }
}
