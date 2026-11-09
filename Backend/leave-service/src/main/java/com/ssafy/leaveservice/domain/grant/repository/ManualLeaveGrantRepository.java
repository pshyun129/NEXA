package com.ssafy.leaveservice.domain.grant.repository;

import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantRequest.GrantMode;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ManualLeaveGrantRepository {
    private final JdbcTemplate jdbc;

    public void lockRequestKey(byte[] digest) {
        ByteBuffer key = ByteBuffer.wrap(digest);
        jdbc.queryForList("select pg_advisory_xact_lock(?, ?)", key.getInt(), key.getInt());
    }

    public Optional<Receipt> findReceipt(String requestKey) {
        return jdbc.query("""
                select receipt.*, grant_row.account_id, grant_row.policy_version_id, grant_row.original_minutes
                from leave_manual_grant_receipt receipt
                join leave_grant grant_row on grant_row.grant_id=receipt.grant_id
                where receipt.request_key=?
                """, (row, index) -> new Receipt(row.getString("payload_hash"), new ManualLeaveGrantResponse(
                row.getLong("account_id"), row.getLong("grant_id"), row.getLong("entry_id"),
                row.getLong("emp_id"), row.getLong("balance_type_id"), row.getLong("policy_version_id"),
                GrantMode.valueOf(row.getString("grant_mode")), row.getInt("original_minutes"),
                row.getLong("remaining_minutes"), row.getObject("created_at", OffsetDateTime.class)
        )), requestKey).stream().findFirst();
    }

    public void lockPolicy(Long policyId, Long versionId) {
        jdbc.queryForList("select policy_id from leave_policy_group where policy_id=? for share", policyId);
        jdbc.queryForList("select policy_version_id from leave_policy_version where policy_version_id=? for share", versionId);
    }

    public boolean lockActiveBalanceType(Long balanceTypeId) {
        return jdbc.queryForList("select is_active from leave_balance_type where balance_type_id=? for share",
                Boolean.class, balanceTypeId).stream().findFirst().orElse(false);
    }

    public boolean hasPaymentHistory(Long employeeId, Long balanceTypeId) {
        // 만료·회수·역분개 이후에도 최초 지급을 반복하지 않고 명시적인 추가 지급을 사용한다.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from leave_ledger ledger join leave_account account using(account_id)
                    where account.emp_id=? and account.balance_type_id=?
                      and ledger.event_type in ('GRANT','CARRY_IN') and ledger.quantity_delta>0
                ) or exists (
                    select 1 from leave_manual_grant_receipt where emp_id=? and balance_type_id=?
                )
                """, Boolean.class, employeeId, balanceTypeId, employeeId, balanceTypeId));
    }

    public void saveReceipt(String requestKey, String payloadHash, Long actorId, ManualLeaveGrantResponse response) {
        jdbc.update("""
                insert into leave_manual_grant_receipt
                (request_key,payload_hash,actor_emp_id,emp_id,balance_type_id,grant_mode,grant_id,entry_id,remaining_minutes,created_at)
                values (?,?,?,?,?,?,?,?,?,?)
                """, requestKey, payloadHash, actorId, response.employeeId(), response.balanceTypeId(),
                response.grantMode().name(), response.grantId(), response.ledgerEntryId(),
                response.remainingMinutes(), response.asOf());
    }

    public record Receipt(String payloadHash, ManualLeaveGrantResponse response) {}
}
