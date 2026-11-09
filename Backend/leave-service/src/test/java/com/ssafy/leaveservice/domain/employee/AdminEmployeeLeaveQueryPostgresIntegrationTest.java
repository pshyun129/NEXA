package com.ssafy.leaveservice.domain.employee;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.account.dto.MyLeaveBalanceResponse;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.domain.ledger.entity.LedgerEventType;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=AdminEmployeeLeaveQueryPostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.employee.AdminEmployeeLeaveQueryPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class AdminEmployeeLeaveQueryPostgresIntegrationTest {

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<List<Boolean>> readOnlyFlags = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<Boolean> gateQuery = ThreadLocal.withInitial(() -> false);

        public SqlCapture() {
        }

        @Override
        public String inspect(String sql) {
            statements.get().add(sql.toLowerCase());
            readOnlyFlags.get().add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            // 격리 DB 동시성 테스트에서만 문장 실행 중의 커밋을 제어한다.
            if (gateQuery.get() && sql.contains("with calculation_time")) {
                gateQuery.remove();
                return sql.replace("where emp_id = ?", "where emp_id = ? and admin_balance_test_gate()");
            }
            return sql;
        }
    }

    private static final String BASE = "/api/v1/leaves/admin/employees/42/";
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2030-01-01T00:00:00Z");
    private static final List<String> TABLES = List.of(
            "leave_ledger", "leave_request", "leave_grant", "leave_accrual_rule", "leave_policy_type_rule",
            "employee_leave_policy_assignment", "leave_account", "leave_type", "leave_balance_type",
            "leave_policy_version", "leave_policy_group"
    );

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MyLeaveBalanceService balanceService;
    @MockitoBean private EmployeeAccess employeeAccess;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        SqlCapture.statements.get().clear();
        SqlCapture.readOnlyFlags.get().clear();
        SqlCapture.gateQuery.remove();
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : TABLES) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(7L);
        when(employeeAccess.isAdmin(7L)).thenReturn(true);
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id,name,is_active) values (1,'연차',true),(2,'보상',false)");
        jdbcTemplate.update("insert into leave_account (account_id,emp_id,balance_type_id) values (901,42,1),(902,42,2),(701,7,1),(42,84,1)");
        jdbcTemplate.update("insert into leave_policy_group (policy_id,name) values (1,'과거 정책'),(2,'다른 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id,policy_id,version_no,base_type)
                values (11,1,1,'HIRE_DATE'),(12,1,2,'HIRE_DATE'),(21,2,1,'HIRE_DATE')
                """);
        jdbcTemplate.update("""
                insert into leave_accrual_rule (rule_id,policy_version_id,frequency,grant_minutes,is_active)
                values (51,11,'YEARLY',480,false)
                """);
        jdbcTemplate.update("""
                insert into leave_type (leave_type_id,balance_type_id,name,balance_mode,request_unit,is_active)
                values (1,1,'연차','ACCOUNT','DAY',false)
                """);
        jdbcTemplate.update("insert into leave_request (leave_req_id,policy_version_id,leave_type_id,emp_id,status) values (71,11,1,42,'CANCELLED')");
        jdbcTemplate.update("""
                insert into leave_grant (grant_id,account_id,policy_version_id,rule_id,parent_grant_id,grant_kind,
                                         granted_on,original_minutes,valid_from,expires_on)
                values (5,901,11,51,null,'AUTO','2020-01-01Z',7200,'2020-01-01Z','2021-01-01Z'),
                       (8,901,null,null,null,'MANUAL','2022-01-01Z',480,'2000-01-01Z',null),
                       (9,901,11,null,5,'CARRY_OVER','2022-01-01Z',120,'3000-01-01Z',null),
                       (12,902,null,null,null,'COMP','2025-01-01Z',240,'2000-01-01Z',null),
                       (70,701,null,null,null,'MANUAL','2098-01-01Z',777,'2000-01-01Z',null),
                       (99,42,null,null,null,'MANUAL','2099-01-01Z',999,'2000-01-01Z',null)
                """);
        jdbcTemplate.update("""
                insert into leave_ledger (entry_id,account_id,grant_id,leave_req_id,reverses_entry_id,event_type,
                                          quantity_delta,reserved_delta,reason,created_at,idempotency_key)
                values (101,901,5,null,null,'GRANT',7200,0,null,'2000-01-01Z','history-grant'),
                       (102,901,5,71,null,'CONSUME',-7200,0,null,'2000-01-01Z','history-consume'),
                       (103,901,8,null,null,'GRANT',480,0,null,'2000-01-01Z','current-grant'),
                       (104,901,8,null,null,'ADJUST',60,0,null,'2000-01-01Z','adjust'),
                       (105,901,8,71,null,'RESERVE',0,120,null,'2000-01-01Z','reserve'),
                       (106,901,8,71,null,'RELEASE',0,-60,null,'2000-01-01Z','release'),
                       (107,901,8,71,null,'CONSUME',-60,-60,null,'2000-01-01Z','consume'),
                       (108,901,8,71,107,'RESTORE',60,0,'  사용 복원  ','2000-01-01Z','restore'),
                       (109,901,8,71,null,'RESERVE',0,120,null,'2000-01-01Z','reserve-again'),
                       (110,901,9,null,null,'CARRY_IN',120,0,null,'2000-01-01Z','carry-in'),
                       (111,902,12,null,null,'GRANT',240,0,null,'2000-01-01Z','comp-grant'),
                       (112,902,12,null,null,'EXPIRE',-60,0,null,'2000-01-01Z','expire'),
                       (113,902,12,null,null,'REVOKE',-30,0,null,'2000-01-01Z','revoke'),
                       (114,902,12,null,null,'CARRY_OUT',-30,0,null,'2001-01-01Z','carry-out'),
                       (170,701,70,null,null,'GRANT',777,0,null,'2000-01-01Z','admin-record'),
                       (199,42,99,null,null,'GRANT',999,0,null,'2099-01-01Z','other-record')
                """);
    }

    @Test
    void balancesReturnsOnlyTargetAccountsAndPreservesStoredRowsAndXmin() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        JsonNode balances = response(BASE + "balances").get("data");
        assertThat(balances).isEqualTo(objectMapper.readTree("""
                [{"accountId":901,"balanceTypeId":1,"balanceTypeName":"연차","quantityMinutes":540,"reservedMinutes":120,"remainingMinutes":420},
                 {"accountId":902,"balanceTypeId":2,"balanceTypeName":"보상","quantityMinutes":120,"reservedMinutes":0,"remainingMinutes":120}]
                """));
        assertOneRead();
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void balancesMatchesUserPathAtSameTimeAndDatabaseSnapshot() {
        try (MockedStatic<OffsetDateTime> time = mockStatic(OffsetDateTime.class, CALLS_REAL_METHODS)) {
            time.when(OffsetDateTime::now).thenReturn(AS_OF);
            time.clearInvocations();
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setReadOnly(true);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status -> {
                List<MyLeaveBalanceResponse> adminBalances = balanceService.getEmployeeBalances(42L);
                when(employeeAccess.currentEmployeeId()).thenReturn(42L);
                assertThat(balanceService.getMyBalances()).isEqualTo(adminBalances);
            });
            time.verify(OffsetDateTime::now, times(2));
        }
        verify(employeeAccess, never()).isAdmin(42L);
    }

    @ParameterizedTest
    @CsvSource({"2030-01-01T00:00:00Z,540", "2030-01-01T00:00:00.000001Z,0"})
    void balancesBothPathsShareExactValidityBoundary(String start, long quantity) {
        jdbcTemplate.update("update leave_grant set valid_from=cast(? as timestamptz) where grant_id=8", start);
        try (MockedStatic<OffsetDateTime> time = mockStatic(OffsetDateTime.class, CALLS_REAL_METHODS)) {
            time.when(OffsetDateTime::now).thenReturn(AS_OF);
            List<MyLeaveBalanceResponse> adminBalances = balanceService.getEmployeeBalances(42L);
            when(employeeAccess.currentEmployeeId()).thenReturn(42L);
            assertThat(balanceService.getMyBalances()).isEqualTo(adminBalances);
            assertThat(adminBalances.getFirst().quantityMinutes()).isEqualTo(quantity);
        }
    }

    @Test
    void balancesNoAccountReturnsEmpty() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/employees/12345/balances"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void balancesInvalidGrantReturnsSameConflictAsUser() throws Exception {
        jdbcTemplate.update("update leave_ledger set reserved_delta=1000 where entry_id=109");
        mvc.perform(get(BASE + "balances")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        mvc.perform(get("/api/v1/leaves/me/balances")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @ParameterizedTest
    @CsvSource({"2030-01-01T00:00:00Z,0", "2030-01-01T00:00:00.000001Z,540"})
    void balancesBothPathsShareExclusiveExpiryBoundary(String expiresOn, long quantity) {
        jdbcTemplate.update("update leave_grant set expires_on=cast(? as timestamptz) where grant_id=8", expiresOn);
        try (MockedStatic<OffsetDateTime> time = mockStatic(OffsetDateTime.class, CALLS_REAL_METHODS)) {
            time.when(OffsetDateTime::now).thenReturn(AS_OF);
            List<MyLeaveBalanceResponse> adminBalances = balanceService.getEmployeeBalances(42L);
            when(employeeAccess.currentEmployeeId()).thenReturn(42L);
            assertThat(balanceService.getMyBalances()).isEqualTo(adminBalances);
            assertThat(adminBalances.getFirst().quantityMinutes()).isEqualTo(quantity);
        }
    }

    @Test
    void balancesConcurrentInsertCannotSplitStatementSnapshot() throws Exception {
        jdbcTemplate.execute("""
                create or replace function admin_balance_test_gate() returns boolean language plpgsql volatile as $$
                begin perform pg_advisory_xact_lock(92425421); return true; end $$
                """);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            writer.setAutoCommit(false);
            writer.createStatement().execute("select pg_advisory_xact_lock(92425421)");
            Future<JsonNode> pendingResponse = executor.submit(() -> {
                SqlCapture.gateQuery.set(true);
                try {
                    return response(BASE + "balances");
                } finally {
                    SqlCapture.gateQuery.remove();
                }
            });
            awaitGateWait();
            writer.createStatement().executeUpdate("""
                    insert into leave_ledger (account_id,grant_id,event_type,quantity_delta,reserved_delta,created_at,idempotency_key)
                    values (901,8,'RESERVE',0,120,'2000-01-01Z','concurrent-reserve'),
                           (901,8,'CONSUME',-120,-120,'2000-01-01Z','concurrent-consume')
                    """);
            writer.commit();
            JsonNode beforeCommit = pendingResponse.get(10, TimeUnit.SECONDS).get("data").get(0);
            assertThat(beforeCommit.get("quantityMinutes").asLong()).isEqualTo(540);
            assertThat(beforeCommit.get("reservedMinutes").asLong()).isEqualTo(120);
            JsonNode afterCommit = response(BASE + "balances").get("data").get(0);
            assertThat(afterCommit.get("quantityMinutes").asLong()).isEqualTo(420);
            assertThat(afterCommit.get("reservedMinutes").asLong()).isEqualTo(120);
            assertThat(afterCommit.get("remainingMinutes").asLong()).isEqualTo(300);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"balances", "grants", "ledger"})
    void permissionsDeniedBeforeAnyDatabaseRead(String endpoint) throws Exception {
        when(employeeAccess.isAdmin(7L)).thenReturn(false);
        mvc.perform(get(BASE + endpoint)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"balances", "grants", "ledger"})
    void permissionsUnauthenticatedCannotQueryEmployee(String endpoint) throws Exception {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        mvc.perform(get(BASE + endpoint)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"balances", "grants", "ledger"})
    void permissionsUserEndpointsRemainScopedToSignedEmployee(String endpoint) throws Exception {
        JsonNode own = response("/api/v1/leaves/me/" + endpoint + "?employeeId=42").get("data");
        assertThat(own.size()).isEqualTo(1);
        assertThat(own.get(0).get("accountId").asLong()).isEqualTo(701);
        JsonNode target = response(BASE + endpoint + "?employeeId=84&accountId=42").get("data");
        for (JsonNode record : target) {
            assertThat(record.get("accountId").asLong()).isIn(901L, 902L);
        }
    }

    private void awaitGateWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbcTemplate.execute("select pg_stat_clear_snapshot()");
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity where wait_event_type='Lock' and wait_event='advisory'
                        and query like '%admin_balance_test_gate()%' and pid<>pg_backend_pid()
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        fail("관리자 잔액 SELECT가 문장 스냅샷 생성 후 테스트 게이트에서 대기해야 한다.");
    }

    private JsonNode response(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void grantsReturnsWholeTargetHistoryWithNullableFieldsAndMatchesUserWithoutWrites() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        JsonNode admin = response(BASE + "grants");
        JsonNode grants = admin.get("data");
        assertThat(ids(grants, "grantId")).containsExactly(12L, 9L, 8L, 5L);
        assertThat(grants.get(1).get("parentGrantId").asLong()).isEqualTo(5);
        assertThat(grants.get(2).get("policyVersionId").isNull()).isTrue();
        assertThat(grants.get(2).get("ruleId").isNull()).isTrue();
        assertThat(grants.get(2).get("expiresOn").isNull()).isTrue();
        assertThat(grants.get(3).get("originalMinutes").asInt()).isEqualTo(7200);
        assertThat(grants.get(3).get("policyVersionId").asLong()).isEqualTo(11);
        assertThat(grants.get(3).get("ruleId").asLong()).isEqualTo(51);
        assertOneRead();
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        assertThat(response("/api/v1/leaves/me/grants")).isEqualTo(admin);
        assertThat(storedRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(longs = {42, 12345})
    void grantsNoRecordsReturnsEmpty(long employeeId) throws Exception {
        jdbcTemplate.update("delete from leave_ledger where account_id in (901,902)");
        jdbcTemplate.update("delete from leave_grant where account_id in (901,902)");
        mvc.perform(get("/api/v1/leaves/admin/employees/" + employeeId + "/grants"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void grantsHistoricalPolicyReferencesSurviveCurrentPolicyChanges() throws Exception {
        JsonNode before = response(BASE + "grants");
        changeCurrentPolicy();
        assertThat(response(BASE + "grants")).isEqualTo(before);
    }

    private void changeCurrentPolicy() {
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=12 where policy_id=1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=21 where policy_id=2");
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (assignment_id,emp_id,policy_id,effective_from)
                values (31,42,2,'2000-01-01Z')
                """);
    }

    @Test
    void ledgerReturnsAllEventsAndStoredDeltasAndMatchesUserWithoutWrites() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        JsonNode admin = response(BASE + "ledger");
        JsonNode entries = admin.get("data");
        assertThat(ids(entries, "entryId")).containsExactly(114L, 113L, 112L, 111L, 110L, 109L, 108L, 107L, 106L, 105L, 104L, 103L, 102L, 101L);
        List<LedgerEventType> eventTypes = new ArrayList<>();
        for (JsonNode entry : entries) {
            assertThat(entry.propertyNames()).hasSize(13);
            eventTypes.add(LedgerEventType.valueOf(entry.get("eventType").asString()));
        }
        assertThat(eventTypes).contains(LedgerEventType.values());
        JsonNode restore = entries.get(6);
        assertThat(restore.get("reversesEntryId").asLong()).isEqualTo(107);
        assertThat(restore.get("leaveReqId").asLong()).isEqualTo(71);
        assertThat(restore.get("reason").asString()).isEqualTo("  사용 복원  ");
        assertThat(restore.get("idempotencyKey").asString()).isEqualTo("restore");
        assertThat(entries.get(7).get("quantityDelta").asInt()).isEqualTo(-60);
        assertThat(entries.get(7).get("reservedDelta").asInt()).isEqualTo(-60);
        assertThat(entries.get(8).get("quantityDelta").asInt()).isZero();
        assertThat(entries.get(8).get("reservedDelta").asInt()).isEqualTo(-60);
        assertThat(entries.get(13).get("leaveReqId").isNull()).isTrue();
        assertThat(entries.get(13).get("reversesEntryId").isNull()).isTrue();
        assertThat(entries.get(13).get("reason").isNull()).isTrue();
        assertOneRead();
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        assertThat(response("/api/v1/leaves/me/ledger")).isEqualTo(admin);
        assertThat(storedRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(longs = {42, 12345})
    void ledgerNoRecordsReturnsEmpty(long employeeId) throws Exception {
        jdbcTemplate.update("delete from leave_ledger where account_id in (901,902)");
        mvc.perform(get("/api/v1/leaves/admin/employees/" + employeeId + "/ledger"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void ledgerHistoricalRecordsSurviveCurrentPolicyChanges() throws Exception {
        JsonNode before = response(BASE + "ledger");
        changeCurrentPolicy();
        assertThat(response(BASE + "ledger")).isEqualTo(before);
    }

    private List<Long> ids(JsonNode records, String idField) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode record : records) {
            ids.add(record.get(idField).asLong());
        }
        return ids;
    }

    private void assertOneRead() {
        assertThat(SqlCapture.statements.get()).hasSize(1).allSatisfy(sql ->
                assertThat(sql).doesNotContain("for update", "for share", "pg_advisory"));
        assertThat(SqlCapture.readOnlyFlags.get()).containsExactly(true);
    }

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }
}
