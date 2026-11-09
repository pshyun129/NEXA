package com.ssafy.leaveservice.domain.grant.repository;

import com.ssafy.leaveservice.domain.grant.entity.LeaveGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LeaveGrantRepository extends JpaRepository<LeaveGrant, Long> {

    @Query("""
            select grantRecord from LeaveGrant grantRecord
            join fetch grantRecord.account account
            join fetch account.balanceType
            where account.employeeId = :employeeId
            order by grantRecord.grantedOn desc, grantRecord.grantId desc
            """)
    List<LeaveGrant> findAllWithAccountAndBalanceTypeByEmployeeId(@Param("employeeId") Long employeeId);
}
