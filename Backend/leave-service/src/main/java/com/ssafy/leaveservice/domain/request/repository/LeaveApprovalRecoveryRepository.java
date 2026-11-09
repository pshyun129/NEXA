package com.ssafy.leaveservice.domain.request.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LeaveApprovalRecoveryRepository {
    private final JdbcTemplate jdbcTemplate;

    public void lockReservationRows(Long requestId, Long employeeId) {
        // 직원 → 신청 → 계정 → 발생분 → allocation → 원장 순서를 유지한다.
        jdbcTemplate.queryForList("""
                select account.account_id from leave_account account
                where account.emp_id=? and exists (
                    select 1 from leave_request_allocation allocation
                    where allocation.leave_req_id=? and allocation.account_id=account.account_id)
                order by account.account_id for update
                """, employeeId, requestId);
        jdbcTemplate.queryForList("""
                select grant_record.grant_id from leave_grant grant_record
                where exists (select 1 from leave_request_allocation allocation
                    where allocation.leave_req_id=? and allocation.grant_id=grant_record.grant_id)
                order by grant_record.grant_id for update
                """, requestId);
        jdbcTemplate.queryForList("""
                select allocation_id from leave_request_allocation where leave_req_id=?
                order by allocation_id for update
                """, requestId);
        jdbcTemplate.queryForList("""
                select entry_id from leave_ledger where leave_req_id=? order by entry_id for update
                """, requestId);
    }
}
