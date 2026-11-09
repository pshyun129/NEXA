package com.ssafy.leaveservice.domain.grant;

import com.ssafy.leaveservice.client.attendance.HolidayClient;
import com.ssafy.leaveservice.client.hr.HrClient;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantRequest;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantRequest.GrantMode;
import com.ssafy.leaveservice.domain.grant.dto.ManualLeaveGrantResponse;
import com.ssafy.leaveservice.domain.grant.service.ManualLeaveGrantService;
import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentRequest;
import com.ssafy.leaveservice.domain.adjustment.service.LeaveAdjustmentService;
import com.ssafy.leaveservice.domain.request.dto.*;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.service.*;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.support.TestTokens;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=ManualLeaveGrantPostgresIntegrationTest",
        "leave.hr.enabled=true", "leave.hr.jwt-claims.duty-id=dutyId",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.jpa.open-in-view=false", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ManualLeaveGrantPostgresIntegrationTest {
    private static final String URL = "/api/v1/leaves/admin/employees/42/grants";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ManualLeaveGrantService service;
    @Autowired LeaveAdjustmentService adjustments;
    @Autowired LeaveSubmissionService submissions;
    @Autowired LeaveApprovalResultService approvalLinks;
    @Autowired LeaveApprovalSettlementService settlements;
    @MockitoBean HrClient hr;
    @MockitoBean HolidayClient holidays;
    private OffsetDateTime start;
    private OffsetDateTime end;

    @BeforeEach
    void setUp() {
        TestTokens.authenticate(10, "USER");
        when(hr.isAvailable()).thenReturn(true);
        when(hr.findEmployee(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Long id = call.getArgument(0);
            return new HrClient.EmployeeInfo(id, 1L, id, null, null, HrClient.EmploymentStatus.EMPLOYED);
        });
        when(hr.findDuty(any())).thenAnswer(call -> new HrClient.DutyInfo(call.getArgument(0),
                Long.valueOf(10).equals(call.getArgument(0)) ? "휴가관리자" : "팀장"));
        when(holidays.findHolidays(any(), any())).thenReturn(new HolidayClient.HolidayResult(true, List.of()));
        jdbc.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : List.of("leave_manual_grant_receipt", "leave_ledger", "leave_request_allocation",
                "leave_request_date", "leave_request", "leave_grant", "leave_policy_type_rule",
                "leave_accrual_rule", "employee_leave_policy_assignment", "leave_account", "leave_type",
                "leave_balance_type", "leave_policy_version", "leave_policy_group")) {
            jdbc.update("delete from " + table);
        }
        start = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).withNano(0);
        end = start.plusYears(1);
        jdbc.update("insert into leave_balance_type(balance_type_id,name) values(1,'연차')");
        jdbc.update("insert into leave_policy_group(policy_id,name) values(1,'정책')");
        jdbc.update("insert into leave_policy_version(policy_version_id,policy_id,version_no,base_type,unit_minutes) values(11,1,1,'HIRE_DATE',30)");
        jdbc.update("update leave_policy_group set active_policy_version_id=11");
        jdbc.update("insert into employee_leave_policy_assignment(emp_id,policy_id,effective_from) values(42,1,'2000-01-01Z'),(43,1,'2000-01-01Z')");
        jdbc.update("insert into leave_type(leave_type_id,balance_type_id,name,balance_mode,request_unit) values(1,1,'연차','ACCOUNT','MIXED')");
        jdbc.update("insert into leave_policy_type_rule(policy_version_id,leave_type_id,allowed_units) values(11,1,'MIXED')");
    }

    @AfterEach
    void clear() { TestTokens.clear(); }

    @Test
    void createsFirstAccountGrantLedgerAndExposesAllExistingQueries() throws Exception {
        mvc.perform(post(URL).with(jwt().jwt(token -> token.subject("10").claim("role", "USER").claim("dutyId", 10)))
                        .header("Idempotency-Key", "initial").contentType("application/json").content(body()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").isEmpty()).andExpect(jsonPath("$.data.grantMinutes").value(7200))
                .andExpect(jsonPath("$.data.remainingMinutes").value(7200)).andExpect(jsonPath("$.data.policyVersionId").value(11));
        assertThat(jdbc.queryForObject("select count(*) from leave_account where emp_id=42", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from leave_grant where grant_kind='MANUAL' and rule_id is null and parent_grant_id is null", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type='GRANT' and quantity_delta=7200 and reserved_delta=0 and actor_emp_id=10 and reason='신규 지급'", Integer.class)).isEqualTo(1);
        for (String path : List.of("balances", "grants", "ledger")) {
            mvc.perform(get("/api/v1/leaves/admin/employees/42/" + path)
                            .with(jwt().jwt(token -> token.subject("10").claim("dutyId", 10))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").exists());
            mvc.perform(get("/api/v1/leaves/me/" + path).with(jwt().jwt(token -> token.subject("42"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").exists());
        }
        mvc.perform(get("/api/v1/leaves/me/summary").with(jwt().jwt(token -> token.subject("42"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.balances[0].remainingMinutes").value(7200));
    }

    @Test
    void reusesEmptyAccountAndStoresMatchingGrantForeignKeys() {
        jdbc.update("insert into leave_account(emp_id,balance_type_id) values(42,1)");
        Long accountId = jdbc.queryForObject("select account_id from leave_account", Long.class);
        ManualLeaveGrantResponse response = service.grant(42L, "key", request(GrantMode.INITIAL));
        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(jdbc.queryForObject("select grant_id from leave_ledger", Long.class)).isEqualTo(response.grantId());
    }

    @Test
    void replayReturnsOriginalResponseEvenAfterBalanceAndActivePolicyChange() {
        ManualLeaveGrantResponse first = service.grant(42L, "key", request(GrantMode.INITIAL));
        adjustments.adjust(42L, "adjust", new LeaveAdjustmentRequest(first.accountId(), first.grantId(), -30, "보정"));
        jdbc.update("update leave_policy_group set active_policy_version_id=null");
        assertThat(service.grant(42L, "key", request(GrantMode.INITIAL))).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from leave_grant", Integer.class)).isEqualTo(1);
    }

    @Test
    void changedBodyOrEmployeeWithSameKeyConflicts() {
        service.grant(42L, "key", request(GrantMode.INITIAL));
        assertError(() -> service.grant(42L, "key", request(GrantMode.ADDITIONAL)), ErrorCode.LEAVE_GRANT_KEY_CONFLICT);
        assertError(() -> service.grant(43L, "key", request(GrantMode.INITIAL)), ErrorCode.LEAVE_GRANT_KEY_CONFLICT);
    }

    @Test
    void initialCannotRepeatWithDifferentKeyButExplicitAdditionalIsAllowed() {
        service.grant(42L, "first", request(GrantMode.INITIAL));
        assertError(() -> service.grant(42L, "another", request(GrantMode.INITIAL)), ErrorCode.LEAVE_GRANT_NOT_ALLOWED);
        assertThat(service.grant(42L, "extra", request(GrantMode.ADDITIONAL)).remainingMinutes()).isEqualTo(14400);
        assertThat(service.grant(43L, "separate", request(GrantMode.INITIAL)).remainingMinutes()).isEqualTo(7200);
    }

    @Test
    void additionalCannotHideFirstPaymentIntent() {
        assertError(() -> service.grant(42L, "extra", request(GrantMode.ADDITIONAL)), ErrorCode.LEAVE_GRANT_NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GRANT", "CARRY_IN"})
    void legacyPaymentPreventsInitialEvenAfterExpiry(String event) {
        jdbc.update("insert into leave_account(account_id,emp_id,balance_type_id) values(901,42,1)");
        jdbc.update("insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from,expires_on) values(901,901,'MANUAL',480,'2000-01-01Z','2001-01-01Z')");
        jdbc.update("insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,idempotency_key) values(901,901,?,480,'legacy')", event);
        assertError(() -> service.grant(42L, "key", request(GrantMode.INITIAL)), ErrorCode.LEAVE_GRANT_NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"leave_grant", "leave_ledger", "leave_manual_grant_receipt"})
    void storageFailureRollsBackAccountGrantLedgerAndReceipt(String table) {
        jdbc.execute("create function reject_payment() returns trigger language plpgsql as $$ begin raise exception 'test failure'; end $$");
        jdbc.execute("create trigger reject_payment before insert on " + table + " for each row execute function reject_payment()");
        try {
            assertThatThrownBy(() -> service.grant(42L, "key", request(GrantMode.INITIAL))).isInstanceOf(RuntimeException.class);
            for (String checked : List.of("leave_account", "leave_grant", "leave_ledger", "leave_manual_grant_receipt")) {
                assertThat(jdbc.queryForObject("select count(*) from " + checked, Integer.class)).isZero();
            }
        } finally {
            jdbc.execute("drop trigger reject_payment on " + table);
            jdbc.execute("drop function reject_payment()");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"same", "different"})
    void concurrentInitialPaymentHappensExactlyOnce(String mode) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        try {
            List<Future<Object>> results = new java.util.ArrayList<>();
            for (int index = 0; index < 2; index++) {
                String key = mode.equals("same") ? "key" : "key" + index;
                results.add(executor.submit(() -> {
                    TestTokens.authenticate(10, "USER");
                    try {
                        startSignal.await(10, TimeUnit.SECONDS);
                        return service.grant(42L, key, request(GrantMode.INITIAL));
                    } catch (BusinessException failure) {
                        return failure.getErrorCode();
                    } finally { TestTokens.clear(); }
                }));
            }
            startSignal.countDown();
            Object first = results.get(0).get(30, TimeUnit.SECONDS);
            Object second = results.get(1).get(30, TimeUnit.SECONDS);
            if (mode.equals("same")) {
                assertThat(first).isEqualTo(second).isInstanceOf(ManualLeaveGrantResponse.class);
            } else {
                assertThat(List.of(first, second)).contains(ErrorCode.LEAVE_GRANT_NOT_ALLOWED);
            }
            assertThat(jdbc.queryForObject("select count(*) from leave_ledger", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from leave_account", Integer.class)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void newGrantSupportsT1ReserveAndFinalSettlement(String finalStatus) {
        ManualLeaveGrantResponse payment = service.grant(42L, "initial", request(GrantMode.INITIAL));
        TestTokens.authenticate(42, "USER");
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(7)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LeaveSubmissionResult submitted = submissions.submit("request", new LeaveRequestPreviewRequest(1L, "휴가",
                List.of(new LeaveRequestPreviewRequest.DateRequest(date, LeaveRequestPortion.FULL))));
        assertThat(jdbc.queryForObject("select grant_id from leave_request_allocation", Long.class)).isEqualTo(payment.grantId());
        approvalLinks.startCall(submitted.requestId(), submitted.approvalRequestId());
        approvalLinks.linkCreatedDocument(submitted.requestId(), submitted.approvalRequestId(), 900L);
        LeaveApprovalDecision decision = new LeaveApprovalDecision(1, "DOCUMENT_COMPLETED", "LEAVE", "LEAVE",
                submitted.requestId(), 900L, submitted.approvalRequestId(), finalStatus);
        assertThat(settlements.settle(decision)).isEqualTo(LeaveApprovalSettlementService.Outcome.APPLIED);
        assertThat(jdbc.queryForObject("select sum(quantity_delta) from leave_ledger", Long.class))
                .isEqualTo(finalStatus.equals("APPROVED") ? 6720L : 7200L);
        assertThat(jdbc.queryForObject("select sum(reserved_delta) from leave_ledger", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"grantMinutes\":0}", "{\"grantMode\":\"AUTO\"}"})
    void invalidBodyReturns400(String body) throws Exception {
        mvc.perform(post(URL).with(jwt().jwt(token -> token.subject("10").claim("dutyId", 10)))
                        .header("Idempotency-Key", "key").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void missingKeyAndNonAdministratorAreRejected() throws Exception {
        mvc.perform(post(URL).with(jwt().jwt(token -> token.subject("10").claim("dutyId", 10)))
                        .contentType("application/json").content(body())).andExpect(status().isBadRequest());
        mvc.perform(post(URL).with(jwt().jwt(token -> token.subject("42").claim("role", "ADMIN").claim("dutyId", 42)))
                        .header("Idempotency-Key", "key").contentType("application/json").content(body()))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrFailureAndTerminatedEmployeeCannotCreateRows() {
        when(hr.findEmployee(42L)).thenReturn(new HrClient.EmployeeInfo(42L, 1L, 1L, null, null, HrClient.EmploymentStatus.TERMINATED));
        assertError(() -> service.grant(42L, "key", request(GrantMode.INITIAL)), ErrorCode.LEAVE_GRANT_NOT_ALLOWED);
        when(hr.findEmployee(42L)).thenReturn(null);
        assertError(() -> service.grant(42L, "key", request(GrantMode.INITIAL)), ErrorCode.HR_VERIFICATION_UNAVAILABLE);
        assertThat(jdbc.queryForObject("select count(*) from leave_account", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete from employee_leave_policy_assignment", "update leave_policy_group set active_policy_version_id=null",
            "update leave_balance_type set is_active=false", "delete from leave_policy_type_rule; delete from leave_type; delete from leave_balance_type"})
    void missingPolicyOrBalanceTypeCannotCreateRows(String sql) {
        jdbc.execute(sql);
        assertThatThrownBy(() -> service.grant(42L, "key", request(GrantMode.INITIAL))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select count(*) from leave_grant", Integer.class)).isZero();
    }

    @Test
    void futureGrantHasZeroCurrentBalanceAndExpiredGrantIsRejected() {
        ManualLeaveGrantRequest future = new ManualLeaveGrantRequest(1L, GrantMode.INITIAL, 7200, end, end.plusYears(1), "미래 지급");
        assertThat(service.grant(42L, "future", future).remainingMinutes()).isZero();
        ManualLeaveGrantRequest expired = new ManualLeaveGrantRequest(1L, GrantMode.INITIAL, 7200, start.minusYears(2), start, "만료 지급");
        assertError(() -> service.grant(43L, "expired", expired), ErrorCode.LEAVE_GRANT_NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"grantMinutes\":1.5", "\"grantMinutes\":\"7200\"", "\"grantMinutes\":2147483648",
            "\"balanceTypeId\":1.5", "\"reason\":123", "\"policyVersionId\":11", "\"employeeId\":43"})
    void wrongTypesAndUnknownFieldsCannotBeSilentlyCoerced(String field) throws Exception {
        String invalid = body().replaceFirst("\\{", "{" + field + ",");
        // 같은 필드를 중복 입력하지 않고 잘못된 타입 하나만 검증한다.
        if (field.startsWith("\"grantMinutes\"")) {
            invalid = body().replace("\"grantMinutes\":7200", field);
        } else if (field.startsWith("\"balanceTypeId\"")) {
            invalid = body().replace("\"balanceTypeId\":1", field);
        } else if (field.startsWith("\"reason\"")) {
            invalid = body().replace("\"reason\":\"신규 지급\"", field);
        }
        mvc.perform(post(URL).with(jwt().jwt(token -> token.subject("10").claim("dutyId", 10)))
                        .header("Idempotency-Key", "key").contentType("application/json").content(invalid))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger", Integer.class)).isZero();
    }

    @Test
    void corruptExistingGrantCannotBeHiddenByNewPositiveGrant() {
        ManualLeaveGrantResponse initial = service.grant(42L, "initial", request(GrantMode.INITIAL));
        jdbc.update("insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,idempotency_key) values(?,?,'ADJUST',-7201,'bad')",
                initial.accountId(), initial.grantId());
        assertError(() -> service.grant(42L, "extra", request(GrantMode.ADDITIONAL)), ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(jdbc.queryForObject("select count(*) from leave_grant", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADJUST", "RESERVE"})
    void employeeLockWaitReadsCommittedAdjustmentOrReservation(String event) throws Exception {
        ManualLeaveGrantResponse initial = service.grant(42L, "initial", request(GrantMode.INITIAL));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (java.sql.Connection connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("select pg_advisory_xact_lock(-42::bigint)");
            }
            Future<ManualLeaveGrantResponse> pending = executor.submit(() -> {
                TestTokens.authenticate(10, "USER");
                try { return service.grant(42L, "extra", request(GrantMode.ADDITIONAL)); }
                finally { TestTokens.clear(); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event='advisory'", Integer.class) == 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(pending.isDone()).isFalse();
            try (java.sql.PreparedStatement statement = connection.prepareStatement("""
                    insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,reserved_delta,idempotency_key)
                    values(?,?,?,?,?,'concurrent')
                    """)) {
                statement.setLong(1, initial.accountId());
                statement.setLong(2, initial.grantId());
                statement.setString(3, event);
                statement.setInt(4, event.equals("ADJUST") ? -240 : 0);
                statement.setInt(5, event.equals("RESERVE") ? 240 : 0);
                statement.executeUpdate();
            }
            connection.commit();
            assertThat(pending.get(20, TimeUnit.SECONDS).remainingMinutes()).isEqualTo(14160);
        } finally { executor.shutdownNow(); }
    }

    private ManualLeaveGrantRequest request(GrantMode mode) {
        return new ManualLeaveGrantRequest(1L, mode, 7200, start, end, "신규 지급");
    }

    private String body() {
        return "{\"balanceTypeId\":1,\"grantMode\":\"INITIAL\",\"grantMinutes\":7200,\"validFrom\":\""
                + start + "\",\"expiresOn\":\"" + end + "\",\"reason\":\"신규 지급\"}";
    }

    private void assertError(Runnable action, ErrorCode error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(error));
    }
}
