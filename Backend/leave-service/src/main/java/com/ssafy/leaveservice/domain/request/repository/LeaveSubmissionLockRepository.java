package com.ssafy.leaveservice.domain.request.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.ssafy.leaveservice.global.exception.*;
import java.time.*;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class LeaveSubmissionLockRepository {
    private final JdbcTemplate jdbc;

    public void lockEmployee(Long employeeId) {
        // 기존 정책 배정과 동일한 직원 잠금 영역을 공유한다.
        jdbc.queryForList("select pg_advisory_xact_lock(-cast(? as bigint))", employeeId);
    }

    public LockedPolicy lockSettings(Long employeeId, Long leaveTypeId) {
        // 직원 잠금 이후 현재 배정 후보를 찾고 그룹부터 일관된 순서로 잠근다.
        List<Long> groups = jdbc.queryForList("""
                select policy_id from employee_leave_policy_assignment
                where emp_id=? and effective_from<=clock_timestamp()
                  and (effective_to is null or effective_to>clock_timestamp())
                """, Long.class, employeeId);
        if (groups.isEmpty()) {
            throw new BusinessException(ErrorCode.CURRENT_LEAVE_POLICY_NOT_FOUND);
        }
        if (groups.size() != 1) {
            throw new BusinessException(ErrorCode.CURRENT_LEAVE_POLICY_CONFLICT);
        }
        Long policyId = groups.getFirst();
        List<Long> versions = jdbc.query("""
                select active_policy_version_id from leave_policy_group where policy_id=? for share
                """, (row, index) -> row.getObject(1, Long.class), policyId);
        if (versions.isEmpty() || versions.getFirst() == null) {
            throw new BusinessException(ErrorCode.CURRENT_LEAVE_POLICY_CONFLICT);
        }
        Long versionId = versions.getFirst();
        jdbc.queryForList("select policy_version_id from leave_policy_version where policy_version_id=? and policy_id=? for share",
                versionId, policyId);
        List<String> modes = jdbc.queryForList("select balance_mode from leave_type where leave_type_id=? for share", String.class, leaveTypeId);
        jdbc.queryForList("""
                select rule_id from leave_policy_type_rule where policy_version_id=? and leave_type_id=?
                order by rule_id for share
                """, versionId, leaveTypeId);

        // NONE은 계정·발생분을 잠그거나 조회하지 않는다.
        if (modes.isEmpty() || !modes.getFirst().equals("ACCOUNT")) {
            return new LockedPolicy(policyId, versionId);
        }
        List<Long> accounts = jdbc.queryForList("""
                select account.account_id from leave_account account join leave_type type
                    on type.balance_type_id=account.balance_type_id and type.balance_mode='ACCOUNT'
                where account.emp_id=? and type.leave_type_id=? order by account.account_id for update of account
                """, Long.class, employeeId, leaveTypeId);
        for (Long accountId : accounts) {
            jdbc.queryForList("select grant_id from leave_grant where account_id=? order by grant_id for update", accountId);
        }
        return new LockedPolicy(policyId, versionId);
    }

    public OffsetDateTime currentTime() {
        // 트랜잭션 시작 시각 대신 모든 잠금 대기 후의 실제 DB 시각을 사용한다.
        return jdbc.queryForObject("select clock_timestamp()", (row, index) -> row.getObject(1, OffsetDateTime.class));
    }

    public record LockedPolicy(Long policyId, Long versionId) {}
}
