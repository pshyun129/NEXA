package com.ssafy.leaveservice.domain.adjustment;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.client.attendance.HolidayClient;
import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentRequest;
import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentResponse;
import com.ssafy.leaveservice.domain.adjustment.service.LeaveAdjustmentService;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewRequest;
import com.ssafy.leaveservice.domain.request.dto.LeaveSubmissionResult;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.service.LeaveApprovalResultService;
import com.ssafy.leaveservice.domain.request.service.LeaveSubmissionService;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.ssafy.leaveservice.support.TestTokens;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "leave.test.database-owner=LeaveAdjustmentPostgresIntegrationTest",
        "leave.hr.enabled=true","leave.hr.jwt-claims.duty-id=dutyId",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class LeaveAdjustmentPostgresIntegrationTest {

    private static final String URL = "/api/v1/leaves/admin/employees/42/adjustments";

    @Autowired
    private PostgreSQLContainer postgres;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LeaveAdjustmentService adjustments;
    @Autowired LeaveSubmissionService submissions;
    @Autowired LeaveApprovalResultService approvalResults;
    @Autowired Flyway flyway;
    @MockitoBean HolidayClient holidays;
    @MockitoBean com.ssafy.leaveservice.client.hr.HrClient hrClient;

    @BeforeEach
    void setUp() {
        when(hrClient.isAvailable()).thenReturn(true);
        when(hrClient.findEmployee(any())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            org.assertj.core.api.Assertions.assertThat(
                    org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new com.ssafy.leaveservice.client.hr.HrClient.EmployeeInfo(id,1L,id,null,null,
                    com.ssafy.leaveservice.client.hr.HrClient.EmploymentStatus.EMPLOYED);
        });
        when(hrClient.findDuty(any())).thenAnswer(call ->
                new com.ssafy.leaveservice.client.hr.HrClient.DutyInfo(call.getArgument(0),"휴가관리자"));
        TestTokens.authenticate(10, "ADMIN");
        jdbc.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : List.of("leave_ledger", "leave_request_allocation", "leave_request_date", "leave_request",
                "leave_grant", "leave_accrual_rule", "leave_policy_type_rule", "employee_leave_policy_assignment",
                "leave_account", "leave_type", "leave_balance_type", "leave_policy_version", "leave_policy_group")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("insert into leave_balance_type(balance_type_id,name) values(1,'연차'),(2,'대체 휴가')");
        jdbc.update("insert into leave_account(account_id,emp_id,balance_type_id) values(901,42,1),(902,43,1),(903,42,2)");
        jdbc.update("""
                insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from)
                values(1001,901,'MANUAL',99999,'2000-01-01Z'),(1002,902,'AUTO',480,'2000-01-01Z'),
                      (1003,903,'COMP',480,'2000-01-01Z'),(1004,901,'CARRY_OVER',480,'2000-01-01Z')
                """);
        jdbc.update("""
                insert into leave_ledger(entry_id,account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                values(1,901,1001,'GRANT',480,'seed1','2000-01-01Z'),(2,902,1002,'GRANT',480,'seed2','2000-01-01Z'),
                      (3,903,1003,'GRANT',480,'seed3','2000-01-01Z'),(4,901,1004,'CARRY_IN',480,'seed4','2000-01-01Z')
                """);
        jdbc.execute("select setval(pg_get_serial_sequence('leave_ledger','entry_id'),100)");
        when(holidays.findHolidays(any(), any())).thenReturn(new HolidayClient.HolidayResult(true, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        TestTokens.clear();
    }

    @ParameterizedTest
    @CsvSource({"480,960", "-240,240", "-480,0"})
    void appendsSingleAdjustPreservesAllExistingRowsAndReportsRealActor(int delta, int expectedQuantity) throws Exception {
        List<Map<String, Object>> before = jdbc.queryForList("select *,xmin::text as row_version from leave_ledger order by entry_id");
        Map<String, Object> grant = jdbc.queryForMap("select *,xmin::text as row_version from leave_grant where grant_id=1001");
        mvc.perform(post(URL).with(admin(10)).header("Idempotency-Key", "normal")
                        .contentType("application/json").content(body(delta)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").isEmpty()).andExpect(jsonPath("$.data.employeeId").value(42))
                .andExpect(jsonPath("$.data.accountId").value(901)).andExpect(jsonPath("$.data.grantId").value(1001))
                .andExpect(jsonPath("$.data.eventType").value("ADJUST"))
                .andExpect(jsonPath("$.data.quantityDelta").value(delta)).andExpect(jsonPath("$.data.reservedDelta").value(0))
                .andExpect(jsonPath("$.data.actorEmpId").value(10)).andExpect(jsonPath("$.data.reason").value(" 정정 "))
                .andExpect(jsonPath("$.data.createdAt").exists());
        assertThat(jdbc.queryForList("select *,xmin::text as row_version from leave_ledger where entry_id<=4 order by entry_id")).isEqualTo(before);
        assertThat(jdbc.queryForMap("select *,xmin::text as row_version from leave_grant where grant_id=1001")).isEqualTo(grant);
        assertThat(quantity()).isEqualTo(expectedQuantity);
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where actor_emp_id is not null", Integer.class)).isEqualTo(1);
        Map<String, Object> saved = jdbc.queryForMap("select * from leave_ledger where actor_emp_id=10");
        for (String field : List.of("leave_req_id", "allocation_id", "reverses_entry_id")) {
            assertThat(saved.get(field)).isNull();
        }
        mvc.perform(get("/api/v1/leaves/admin/employees/42/balances").with(admin(10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].quantityMinutes").value(expectedQuantity + 480));
        mvc.perform(get("/api/v1/leaves/admin/employees/42/ledger").with(admin(10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].eventType").value("ADJUST"))
                .andExpect(jsonPath("$.data[0].actorEmpId").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"accountId\":901,\"grantId\":1001,\"quantityDelta\":0,\"reason\":\"정정\"}",
            "{\"accountId\":901,\"grantId\":1001,\"quantityDelta\":1.5,\"reason\":\"정정\"}",
            "{\"accountId\":901,\"grantId\":1001,\"quantityDelta\":2147483648,\"reason\":\"정정\"}",
            "{\"accountId\":901,\"grantId\":1001,\"quantityDelta\":-2147483649,\"reason\":\"정정\"}",
            "{\"accountId\":901,\"grantId\":1001,\"quantityDelta\":1}",
            "{\"accountId\":901,\"grantId\":1001,\"quantityDelta\":1,\"reason\":null}",
            "{\"accountId\":901,\"grantId\":1001,\"quantityDelta\":1,\"reason\":\" \"}",
            "{\"accountId\":0,\"grantId\":1001,\"quantityDelta\":1,\"reason\":\"정정\"}",
            "{\"accountId\":901,\"grantId\":-1,\"quantityDelta\":1,\"reason\":\"정정\"}",
            "{\"accountId\":901,\"grantId\":1001,\"quantityDelta\":1,\"reason\":\"정정\",\"actorEmpId\":999}"})
    void invalidBodyReturns400WithoutInsert(String body) throws Exception {
        mvc.perform(post(URL).with(admin(10)).header("Idempotency-Key", "invalid")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(adjustmentCount()).isZero();
    }

    @Test
    void reason500BoundaryAndWhitespacePreservation() throws Exception {
        mvc.perform(post(URL).with(admin(10)).header("Idempotency-Key", "500").contentType("application/json")
                        .content(body(1).replace(" 정정 ", "가".repeat(500))))
                .andExpect(status().isCreated());
        mvc.perform(post(URL).with(admin(10)).header("Idempotency-Key", "501").contentType("application/json")
                        .content(body(1).replace(" 정정 ", "가".repeat(501))))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "", " "})
    void missingOrBlankKeyRejected(String key) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = post(URL).with(admin(10))
                .contentType("application/json").content(body(1));
        if (!key.equals("missing")) {
            request.header("Idempotency-Key", key);
        }
        mvc.perform(request).andExpect(status().isBadRequest());
        assertThat(adjustmentCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "text", "9223372036854775808"})
    void invalidEmployeeIdReturns400(String employeeId) throws Exception {
        mvc.perform(post(URL.replace("42", employeeId)).with(admin(10)).header("Idempotency-Key", "invalid")
                        .contentType("application/json").content(body(1)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({"99,901,1001", "42,999,1001", "42,901,999", "42,902,1002", "42,901,1003"})
    void enforcesAccountAndGrantOwnership(long employeeId, long accountId, long grantId) {
        assertBusiness(() -> adjustments.adjust(employeeId, "owner", new LeaveAdjustmentRequest(accountId, grantId, 1, "정정")), ErrorCode.NOT_FOUND);
        assertThat(adjustmentCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "expired", "future", "noLedger", "adjustOnly", "reversedCredit", "futureCredit", "creditReserved"})
    void rejectsIneligibleGrantOrMissingEffectiveCredit(String scenario) {
        switch (scenario) {
            case "inactive" -> jdbc.update("update leave_balance_type set is_active=false where balance_type_id=1");
            case "expired" -> jdbc.update("update leave_grant set expires_on='2001-01-01Z' where grant_id=1001");
            case "future" -> jdbc.update("update leave_grant set valid_from='2900-01-01Z' where grant_id=1001");
            case "noLedger" -> jdbc.update("delete from leave_ledger where grant_id=1001");
            case "adjustOnly" -> jdbc.update("update leave_ledger set event_type='ADJUST' where grant_id=1001");
            case "reversedCredit" -> jdbc.update("""
                    insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,reverses_entry_id,idempotency_key)
                    values(901,1001,'REVOKE',-480,1,'reversal')
                    """);
            case "futureCredit" -> jdbc.update("update leave_ledger set created_at='2900-01-01Z' where grant_id=1001");
            case "creditReserved" -> jdbc.update("update leave_ledger set reserved_delta=1 where grant_id=1001");
            default -> fail("unknown scenario");
        }
        assertBusiness(() -> adjust("ineligible", 1), ErrorCode.LEAVE_ADJUSTMENT_NOT_ALLOWED);
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where actor_emp_id is not null", Integer.class)).isZero();
    }

    @Test
    void carryInIsCreditAndOriginalMinutesNeedNotMatch() {
        LeaveAdjustmentResponse response = adjustments.adjust(42L, "carry", new LeaveAdjustmentRequest(901L, 1004L, 100, "이월 정정"));
        assertThat(response.quantityDelta()).isEqualTo(100);
    }

    @Test
    void futureReversalIsExcludedFromBothCreditValidationAndCurrentBalance() throws Exception {
        jdbc.update("""
                insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,reverses_entry_id,idempotency_key,created_at)
                values(901,1001,'REVOKE',-480,1,'future-reversal','2900-01-01Z')
                """);
        adjust("before-reversal", 120);
        mvc.perform(get("/api/v1/leaves/admin/employees/42/balances").with(admin(10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].quantityMinutes").value(1080));
    }

    @Test
    void runsActualInsertInReadCommittedTransaction() {
        jdbc.execute("""
                create or replace function adjustment_isolation() returns trigger language plpgsql as $$
                begin
                    if current_setting('transaction_isolation') <> 'read committed' then
                        raise exception 'READ COMMITTED required';
                    end if;
                    return new;
                end $$
                """);
        jdbc.execute("create trigger adjustment_isolation before insert on leave_ledger for each row execute function adjustment_isolation()");
        try {
            assertThat(adjust("isolation", 1).actorEmpId()).isEqualTo(10);
        } finally {
            jdbc.execute("drop trigger adjustment_isolation on leave_ledger");
        }
    }

    @ParameterizedTest
    @CsvSource({"0,120,-361", "0,120,-481", "-600,0,1", "0,-1,1", "0,481,1"})
    void rejectsReservationInvasionOrInvalidExistingLedger(int quantity, int reserved, int delta) {
        insertDelta(quantity, reserved);
        assertBusiness(() -> adjust("integrity", delta), ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(adjustmentCount()).isEqualTo(quantity == 0 ? 0 : 1);
    }

    @Test
    void exactAvailableDecreasePreservesReservations() {
        insertDelta(0, 120);
        adjust("exact", -360);
        assertThat(quantity()).isEqualTo(120);
        assertThat(jdbc.queryForObject("select sum(reserved_delta) from leave_ledger where grant_id=1001", Long.class)).isEqualTo(120);
    }

    @Test
    void minimumIntegerIsNotNegatedOrWrapped() {
        assertBusiness(() -> adjust("minimum", Integer.MIN_VALUE), ErrorCode.DATA_INTEGRITY_VIOLATION);
        jdbc.update("update leave_ledger set quantity_delta=2147483647 where entry_id=1");
        insertDelta(1, 0);
        adjust("minimum-ok", Integer.MIN_VALUE);
        assertThat(quantity()).isZero();
    }

    @Test
    void aggregateMayExceedIntWhileEachDeltaStaysInt() {
        jdbc.update("update leave_ledger set quantity_delta=2147483647 where entry_id=1");
        adjust("maximum", Integer.MAX_VALUE);
        assertThat(quantity()).isEqualTo(4294967294L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same", "expired", "inactive", "changedBalance"})
    void successfulRetryReturnsStoredIdentityBeforeNewEligibilityChecks(String scenario) {
        LeaveAdjustmentResponse initial = adjust("retry", 120);
        switch (scenario) {
            case "expired" -> jdbc.update("update leave_grant set expires_on='2001-01-01Z' where grant_id=1001");
            case "inactive" -> jdbc.update("update leave_balance_type set is_active=false where balance_type_id=1");
            case "changedBalance" -> insertDelta(-20, 0);
            default -> { }
        }
        assertThat(adjust("retry", 120)).isEqualTo(initial);
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where actor_emp_id=10", Integer.class)).isEqualTo(1);
        String key = jdbc.queryForObject("select idempotency_key from leave_ledger where actor_emp_id=10", String.class);
        assertThat(key).startsWith("leave:admin-adjust:").hasSize(83);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quantity", "grant", "account", "employee", "reason"})
    void sameAdministratorKeyWithDifferentIntentConflicts(String field) {
        adjust("reuse", 120);
        LeaveAdjustmentRequest request = new LeaveAdjustmentRequest(
                field.equals("account") ? 903L : 901L, field.equals("grant") ? 1004L : 1001L,
                field.equals("quantity") ? 121 : 120, field.equals("reason") ? "정정" : " 정정 "
        );
        assertBusiness(() -> adjustments.adjust(field.equals("employee") ? 43L : 42L, "reuse", request), ErrorCode.LEAVE_ADJUSTMENT_KEY_CONFLICT);
        assertThat(adjustmentCount()).isEqualTo(1);
    }

    @Test
    void differentAdministratorsCanUseSameClientKey() {
        LeaveAdjustmentResponse first = adjust("shared", 1);
        TestTokens.authenticate(11, "ADMIN");
        LeaveAdjustmentResponse second = adjust("shared", 1);
        assertThat(second.entryId()).isNotEqualTo(first.entryId());
        assertThat(second.actorEmpId()).isEqualTo(11);
    }

    @Test
    void userAndAnonymousAreDeniedAndRetryStillNeedsAdmin() throws Exception {
        adjust("auth", 1);
        when(hrClient.findDuty(10L)).thenReturn(new com.ssafy.leaveservice.client.hr.HrClient.DutyInfo(10L,"팀원"));
        TestTokens.clear();
        mvc.perform(post(URL).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous())
                        .contentType("application/json").content(body(1)).header("Idempotency-Key", "auth"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(URL).with(jwt().jwt(j -> j.subject("10").claim("role", "USER").claim("type", "access").claim("dutyId",10L))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType("application/json").content(body(1)).header("Idempotency-Key", "auth"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        TestTokens.authenticate(10, "USER");
        assertBusiness(() -> adjust("auth", 1), ErrorCode.ACCESS_DENIED);
        TestTokens.clear();
        assertBusiness(() -> adjust("auth", 1), ErrorCode.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sameKey", "differentAdmins", "differentEmployeesSameKey"})
    void concurrentRequestsRespectEmployeeAndRequestKeyLocks(String mode) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> concurrentAdjustment(start, 10, 42, 901, 1001, "race"));
            Future<Object> second = executor.submit(() -> concurrentAdjustment(start,
                    mode.equals("differentAdmins") ? 11 : 10,
                    mode.equals("differentEmployeesSameKey") ? 43 : 42,
                    mode.equals("differentEmployeesSameKey") ? 902 : 901,
                    mode.equals("differentEmployeesSameKey") ? 1002 : 1001, "race"));
            start.countDown();
            List<Object> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            if (mode.equals("differentEmployeesSameKey")) {
                assertThat(results.stream().filter(BusinessException.class::isInstance)).hasSize(1);
                assertThat(adjustmentCount()).isEqualTo(1);
            } else if (mode.equals("sameKey")) {
                assertThat(results.getFirst()).isEqualTo(results.getLast());
                assertThat(adjustmentCount()).isEqualTo(1);
            } else {
                assertThat(results).allMatch(LeaveAdjustmentResponse.class::isInstance);
                assertThat(adjustmentCount()).isEqualTo(2);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "release"})
    void lockWaitRecomputesLatestBalanceAfterWriterCommit(String mode) throws Exception {
        if (mode.equals("release")) {
            insertDelta(0, 480);
        }
        try (Connection writer = writer(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            writer.createStatement().execute("select pg_advisory_xact_lock(-42)");
            Future<Object> waiting = executor.submit(() -> asAdmin(() -> adjust("wait", -480)));
            awaitLockWait();
            writer.createStatement().executeUpdate("""
                    insert into leave_ledger(account_id,grant_id,event_type,reserved_delta,idempotency_key,created_at)
                    values(901,1001,'%s',%d,'late',clock_timestamp())
                    """.formatted(mode.equals("reserve") ? "RESERVE" : "RELEASE", mode.equals("reserve") ? 120 : -480));
            writer.commit();
            Object result = waiting.get(20, TimeUnit.SECONDS);
            if (mode.equals("reserve")) {
                assertThat(result).isInstanceOf(BusinessException.class);
                assertThat(quantity()).isEqualTo(480);
            } else {
                assertThat(result).isInstanceOf(LeaveAdjustmentResponse.class);
                assertThat(quantity()).isZero();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-240, 480})
    void realT1ReservationAndAdjustmentCompeteUsingSameEmployeeLock(int delta) throws Exception {
        preparePolicy();
        // 비교 대상 발생분 하나만 두어 다른 발생분으로 부족량이 상쇄되지 않게 한다.
        jdbc.update("delete from leave_ledger where grant_id=1004");
        jdbc.update("delete from leave_grant where grant_id=1004");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> adjustment = executor.submit(() -> {
                start.await();
                return asAdmin(() -> adjust("t1-race", delta));
            });
            Future<Object> submission = executor.submit(() -> {
                start.await();
                return submitAsEmployee();
            });
            start.countDown();
            List<Object> results = List.of(adjustment.get(20, TimeUnit.SECONDS), submission.get(20, TimeUnit.SECONDS));
            if (delta < 0) {
                assertThat(results.stream().filter(BusinessException.class::isInstance)).hasSize(1);
            } else {
                assertThat(results).noneMatch(BusinessException.class::isInstance);
                assertThat(quantity()).isEqualTo(960);
            }
            long reserved = jdbc.queryForObject("select sum(reserved_delta) from leave_ledger where grant_id=1001", Long.class);
            assertThat(reserved).isBetween(0L, quantity());
            assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type='RESERVE' and actor_emp_id is not null", Integer.class)).isZero();
        }
    }

    @Test
    void realT2ReleaseAndAdjustmentPreserveAllocationAndAccounting() throws Exception {
        preparePolicy();
        Object submitted = submitAsEmployee();
        assertThat(submitted).isInstanceOf(LeaveSubmissionResult.class);
        LeaveSubmissionResult request = (LeaveSubmissionResult) submitted;
        Map<String, Object> allocation = jdbc.queryForMap("select *,xmin::text as row_version from leave_request_allocation");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> adjustment = executor.submit(() -> {
                start.await();
                return asAdmin(() -> adjust("t2-race", -480));
            });
            Future<LeaveSubmissionResult> release = executor.submit(() -> {
                start.await();
                TestTokens.authenticate(42, "USER");
                try {
                    return approvalResults.confirmNotCreated(request.requestId(), request.approvalRequestId(), "문서 미생성 확정");
                } finally {
                    TestTokens.clear();
                }
            });
            start.countDown();
            Object result = adjustment.get(20, TimeUnit.SECONDS);
            assertThat(release.get(20, TimeUnit.SECONDS).status().name()).isEqualTo("CANCELLED");
            if (result instanceof BusinessException) {
                asAdmin(() -> adjust("t2-race", -480));
            }
            assertThat(quantity()).isZero();
            assertThat(jdbc.queryForObject("select sum(reserved_delta) from leave_ledger where grant_id=1001", Long.class)).isZero();
            assertThat(jdbc.queryForMap("select *,xmin::text as row_version from leave_request_allocation")).isEqualTo(allocation);
            assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type='RELEASE' and actor_emp_id is not null", Integer.class)).isZero();
        }
    }

    @Test
    void insertFailureRollsBackAndSameKeyCanBeRetried() {
        jdbc.execute("""
                create or replace function adjustment_fail() returns trigger language plpgsql as $$
                begin
                    if new.actor_emp_id is not null then raise exception 'forced failure'; end if;
                    return new;
                end $$
                """);
        jdbc.execute("create trigger adjustment_fail before insert on leave_ledger for each row execute function adjustment_fail()");
        try {
            assertThatThrownBy(() -> adjust("rollback", 120)).isInstanceOf(RuntimeException.class);
            assertThat(adjustmentCount()).isZero();
            assertThat(quantity()).isEqualTo(480);
        } finally {
            jdbc.execute("drop trigger adjustment_fail on leave_ledger");
        }
        assertThat(adjust("rollback", 120).quantityDelta()).isEqualTo(120);
    }

    @Test
    void v5PreservesLegacyAdjustAndAddsOnlyNullableActorWithoutHrFk() {
        String schema = "v5_" + UUID.randomUUID().toString().replace("-", "");
        Flyway old = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("4").load();
        old.migrate();
        String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        JdbcTemplate legacy = new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        legacy.update("insert into leave_balance_type(balance_type_id,name) values(1,'과거 잔액')");
        legacy.update("insert into leave_account(account_id,emp_id,balance_type_id) values(1,42,1)");
        legacy.update("insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from) values(1,1,'MANUAL',480,'2000-01-01Z')");
        legacy.update("""
                insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,idempotency_key)
                values(1,1,'GRANT',480,'legacy-grant'),(1,1,'ADJUST',-120,'legacy-adjust')
                """);
        List<Map<String, Object>> before = legacy.queryForList("select to_jsonb(l)::text as row,xmin::text from leave_ledger l order by entry_id");
        Flyway latest = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load();
        latest.migrate();
        latest.validate();
        assertThat(legacy.queryForList("select (to_jsonb(l)-'actor_emp_id')::text as row,xmin::text from leave_ledger l order by entry_id")).isEqualTo(before);
        assertThat(legacy.queryForObject("select count(*) from leave_ledger where actor_emp_id is null", Integer.class)).isEqualTo(2);
        assertThat(legacy.queryForMap("select data_type,is_nullable,column_default from information_schema.columns where table_schema=? and table_name='leave_ledger' and column_name='actor_emp_id'", schema))
                .containsEntry("data_type", "bigint").containsEntry("is_nullable", "YES").containsEntry("column_default", null);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.key_column_usage
                where table_schema=? and table_name='leave_ledger' and column_name='actor_emp_id'
                """, Integer.class, schema)).isZero();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
        assertThat(jdbc.queryForObject("select version()", String.class)).contains("PostgreSQL 17");
    }

    private LeaveAdjustmentResponse adjust(String key, int delta) {
        return adjustments.adjust(42L, key, new LeaveAdjustmentRequest(901L, 1001L, delta, " 정정 "));
    }

    private void insertDelta(int quantity, int reserved) {
        jdbc.update("""
                insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,reserved_delta,idempotency_key,created_at)
                values(901,1001,?, ?, ?, ?, '2001-01-01Z')
                """, quantity == 0 ? "RESERVE" : "ADJUST", quantity, reserved, UUID.randomUUID().toString());
    }

    private long quantity() {
        return jdbc.queryForObject("select sum(quantity_delta) from leave_ledger where grant_id=1001", Long.class);
    }

    private int adjustmentCount() {
        return jdbc.queryForObject("select count(*) from leave_ledger where event_type='ADJUST'", Integer.class);
    }

    private static String body(int delta) {
        return "{\"accountId\":901,\"grantId\":1001,\"quantityDelta\":" + delta + ",\"reason\":\" 정정 \"}";
    }

    private static RequestPostProcessor admin(long employeeId) {
        return jwt().jwt(j -> j.subject(String.valueOf(employeeId)).claim("role", "ADMIN").claim("type", "access").claim("dutyId",employeeId))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static void assertBusiness(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private Object concurrentAdjustment(CountDownLatch start, long admin, long employee, long account, long grant, String key) throws Exception {
        start.await();
        TestTokens.authenticate(admin, "ADMIN");
        try {
            return adjustments.adjust(employee, key, new LeaveAdjustmentRequest(account, grant, 1, "정정"));
        } catch (BusinessException exception) {
            return exception;
        } finally {
            TestTokens.clear();
        }
    }

    private Object asAdmin(Callable<?> operation) throws Exception {
        TestTokens.authenticate(10, "ADMIN");
        try {
            return operation.call();
        } catch (BusinessException exception) {
            return exception;
        } finally {
            TestTokens.clear();
        }
    }

    private Connection writer() throws Exception {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        connection.setAutoCommit(false);
        return connection;
    }

    private void awaitLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and pid<>pg_backend_pid()", Integer.class) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("직원 Advisory Lock 대기가 확인되어야 합니다.");
    }

    private void preparePolicy() {
        jdbc.update("insert into leave_policy_group(policy_id,name) values(1,'정책')");
        jdbc.update("insert into leave_policy_version(policy_version_id,policy_id,version_no,base_type) values(1,1,1,'HIRE_DATE')");
        jdbc.update("update leave_policy_group set active_policy_version_id=1 where policy_id=1");
        jdbc.update("insert into employee_leave_policy_assignment(emp_id,policy_id,effective_from) values(42,1,'2000-01-01Z')");
        jdbc.update("insert into leave_type(leave_type_id,balance_type_id,name,balance_mode,request_unit) values(1,1,'연차','ACCOUNT','MIXED')");
        jdbc.update("insert into leave_policy_type_rule(policy_version_id,leave_type_id,allowed_units) values(1,1,'MIXED')");
    }

    private Object submitAsEmployee() {
        TestTokens.authenticate(42, "USER");
        LocalDate day = LocalDate.now().plusDays(30).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        try {
            return submissions.submit("t1", new LeaveRequestPreviewRequest(1L, "사유", List.of(
                    new LeaveRequestPreviewRequest.DateRequest(day, LeaveRequestPortion.FULL)
            )));
        } catch (BusinessException exception) {
            return exception;
        } finally {
            TestTokens.clear();
        }
    }
}
