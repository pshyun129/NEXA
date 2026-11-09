package com.ssafy.leaveservice.domain.account.repository;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.account.repository.projection.LeaveAccountBalance;
import com.ssafy.leaveservice.domain.account.repository.projection.DepartmentAccountBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import com.ssafy.leaveservice.domain.account.repository.projection.PreviewGrantBalance;

public interface LeaveAccountRepository extends JpaRepository<LeaveAccount, Long> {

    @Query(value = LeaveBalanceSql.GRANT_TOTALS + """
            select grant_id as "grantId", valid_from as "validFromInstant", expires_on as "expiresOnInstant",
                   quantity_minutes as "quantityMinutes", reserved_minutes as "reservedMinutes"
            from grant_totals where grant_id = :grantId
            """, nativeQuery = true)
    java.util.Optional<PreviewGrantBalance> findAdjustmentGrantBalance(
            @Param("employeeId") Long employeeId,
            @Param("grantId") Long grantId,
            @Param("asOf") OffsetDateTime asOf
    );

    // 발생분별 원장을 먼저 집계하고, 같은 기준 시각의 유효 발생분만 계정별로 합산한다.
    // NUMERIC 합계로 DB 중간 집계의 BIGINT 오버플로도 방지한다.
    @Query(value = LeaveBalanceSql.GRANT_TOTALS + "select " + LeaveBalanceSql.ACCOUNT_BALANCE_COLUMNS
            + LeaveBalanceSql.ACCOUNT_BALANCE_FROM + """
            group by account.account_id, balance_type.balance_type_id, balance_type.name
            order by balance_type.balance_type_id, account.account_id
            """, nativeQuery = true)
    List<LeaveAccountBalance> findBalancesByEmployeeId(
            @Param("employeeId") Long employeeId,
            @Param("asOf") OffsetDateTime asOf
    );
    @Query(value = LeaveBalanceSql.GRANT_TOTALS + """
            select grant_total.grant_id as "grantId", grant_total.valid_from as "validFromInstant",
                   grant_total.expires_on as "expiresOnInstant", grant_total.quantity_minutes as "quantityMinutes",
                   grant_total.reserved_minutes as "reservedMinutes"
            from grant_totals grant_total
            join owned_accounts account on account.account_id = grant_total.account_id
            where account.balance_type_id = :balanceTypeId
            order by grant_total.expires_on asc nulls last, grant_total.valid_from asc, grant_total.grant_id asc
            """, nativeQuery = true)
    List<PreviewGrantBalance> findPreviewGrantBalances(
            @Param("employeeId") Long employeeId,
            @Param("balanceTypeId") Long balanceTypeId,
            @Param("asOf") OffsetDateTime asOf
    );


    java.util.Optional<LeaveAccount> findByEmployeeIdAndBalanceTypeBalanceTypeId(Long employeeId, Long balanceTypeId);

    @Query(value = LeaveBalanceSql.GRANT_TOTALS + """
            select grant_total.grant_id as "grantId", grant_total.valid_from as "validFromInstant",
                   grant_total.expires_on as "expiresOnInstant", grant_total.quantity_minutes as "quantityMinutes",
                   grant_total.reserved_minutes as "reservedMinutes"
            from grant_totals grant_total
            """, nativeQuery = true)
    List<PreviewGrantBalance> findRecordedGrantBalances(
            @Param("employeeId") Long employeeId,
            @Param("asOf") OffsetDateTime asOf
    );

    @Query(value = LeaveBalanceSql.DEPARTMENT_GRANT_TOTALS + "select account.emp_id as \"employeeId\", "
            + LeaveBalanceSql.ACCOUNT_BALANCE_COLUMNS + LeaveBalanceSql.ACCOUNT_BALANCE_FROM + """
            group by account.emp_id, account.account_id, balance_type.balance_type_id, balance_type.name
            order by account.emp_id, balance_type.balance_type_id, account.account_id
            """, nativeQuery = true)
    List<DepartmentAccountBalance> findBalancesByEmployeeIds(
            @Param("employeeIds") List<Long> employeeIds,
            @Param("asOf") OffsetDateTime asOf
    );
}
