package com.ssafy.leaveservice.domain.account.repository;

public final class LeaveBalanceSql {
    private LeaveBalanceSql() {}

    private static final String CALCULATION_TIME = """
            with calculation_time as (
                select cast(:asOf as timestamptz) as as_of
            ), owned_accounts as (
            """;

    private static final String GRANT_TOTALS_BODY = """
            ), grant_totals as (
                select grant_record.account_id, grant_record.grant_id,
                       grant_record.valid_from, grant_record.expires_on,
                       coalesce(sum(cast(ledger.quantity_delta as numeric)), 0) as quantity_minutes,
                       coalesce(sum(cast(ledger.reserved_delta as numeric)), 0) as reserved_minutes
                from owned_accounts account
                join leave_grant grant_record on grant_record.account_id = account.account_id
                cross join calculation_time time
                left join leave_ledger ledger on ledger.grant_id = grant_record.grant_id
                    and ledger.account_id = grant_record.account_id and ledger.created_at <= time.as_of
                group by grant_record.account_id, grant_record.grant_id,
                         grant_record.valid_from, grant_record.expires_on
            )
            """;

    // 기존 단일 직원·Preview·보상과 부서 일괄 조회가 같은 delta 계산식을 공유한다.
    public static final String GRANT_TOTALS = CALCULATION_TIME + """
                select account_id, balance_type_id from leave_account where emp_id = :employeeId
            """ + GRANT_TOTALS_BODY;

    public static final String DEPARTMENT_GRANT_TOTALS = CALCULATION_TIME + """
                select account_id, balance_type_id, emp_id from leave_account where emp_id in (:employeeIds)
            """ + GRANT_TOTALS_BODY;

    public static final String ACCOUNT_BALANCE_COLUMNS = """
            account.account_id as "accountId", balance_type.balance_type_id as "balanceTypeId",
                   balance_type.name as "balanceTypeName",
                   coalesce(sum(grant_total.quantity_minutes), 0) as "quantityMinutes",
                   coalesce(sum(grant_total.reserved_minutes), 0) as "reservedMinutes",
                   coalesce(bool_or(grant_total.quantity_minutes < 0 or grant_total.reserved_minutes < 0
                       or grant_total.reserved_minutes > grant_total.quantity_minutes), false) as "invalidGrant"
            """;

    public static final String ACCOUNT_BALANCE_FROM = """
            from owned_accounts account
            join leave_balance_type balance_type on balance_type.balance_type_id = account.balance_type_id
            cross join calculation_time time
            left join grant_totals grant_total on grant_total.account_id = account.account_id
                and grant_total.valid_from <= time.as_of
                and (grant_total.expires_on is null or grant_total.expires_on > time.as_of)
            """;
}
