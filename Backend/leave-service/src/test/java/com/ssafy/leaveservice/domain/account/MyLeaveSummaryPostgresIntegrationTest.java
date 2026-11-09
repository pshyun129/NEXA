package com.ssafy.leaveservice.domain.account;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
        "leave.test.database-owner=MyLeaveSummaryPostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.account.MyLeaveSummaryPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class MyLeaveSummaryPostgresIntegrationTest {

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<List<Boolean>> readOnlyFlags = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<List<Integer>> isolationLevels = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<Boolean> gateQuery = ThreadLocal.withInitial(() -> false);

        public SqlCapture() {
        }

        @Override
        public String inspect(String sql) {
            statements.get().add(sql.toLowerCase());
            readOnlyFlags.get().add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            isolationLevels.get().add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            // 격리된 동시성 테스트에서만 문장 스냅샷 생성 후 실행을 멈춘다.
            if (gateQuery.get() && sql.contains("with calculation_time")) {
                gateQuery.remove();
                return sql.replace("where emp_id = ?", "where emp_id = ? and summary_test_gate()");
            }
            return sql;
        }
    }

    private static final String URL = "/api/v1/leaves/me/summary";
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
    @MockitoSpyBean private LeaveAccountRepository repository;
    @MockitoBean private EmployeeAccess employeeAccess;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        SqlCapture.statements.get().clear();
        SqlCapture.readOnlyFlags.get().clear();
        SqlCapture.isolationLevels.get().clear();
        SqlCapture.gateQuery.remove();
        clearInvocations(repository);
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : TABLES) {
            jdbcTemplate.update("delete from " + table);
        }
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id,name,is_active) values (20,'연차',true),(10,'보상',false)");
        jdbcTemplate.update("insert into leave_account (account_id,emp_id,balance_type_id) values (901,42,20),(902,42,10),(42,84,20)");
        jdbcTemplate.update("""
                insert into leave_grant (grant_id,account_id,parent_grant_id,grant_kind,original_minutes,valid_from,expires_on)
                values (1,901,null,'MANUAL',9999,'2000-01-01Z',null),(2,902,null,'COMP',240,'2000-01-01Z',null),
                       (3,901,1,'CARRY_OVER',120,'3000-01-01Z',null),
                       (4,901,null,'AUTO',480,'2000-01-01Z','2001-01-01Z'),(99,42,null,'MANUAL',999,'2000-01-01Z',null)
                """);
        // 이벤트별 계산을 재구현하지 않고 하나의 복합 흐름으로 기존 집계 재사용을 검증한다.
        jdbcTemplate.update("""
                insert into leave_ledger (entry_id,account_id,grant_id,reverses_entry_id,event_type,
                                          quantity_delta,reserved_delta,created_at,idempotency_key)
                values (101,901,1,null,'GRANT',480,0,'2000-01-01Z','grant'),
                       (102,901,1,null,'RESERVE',0,240,'2000-01-01Z','reserve'),
                       (103,901,1,null,'CONSUME',-120,-120,'2000-01-01Z','consume'),
                       (104,901,1,null,'RELEASE',0,-60,'2000-01-01Z','release'),
                       (105,901,1,103,'RESTORE',60,0,'2000-01-01Z','restore'),
                       (106,901,1,null,'ADJUST',30,0,'2000-01-01Z','adjust'),
                       (107,901,1,null,'EXPIRE',-30,0,'2000-01-01Z','expire'),
                       (108,901,1,null,'REVOKE',-60,0,'2000-01-01Z','revoke'),
                       (109,901,1,null,'CARRY_OUT',-60,0,'2000-01-01Z','carry-out'),
                       (110,902,2,null,'CARRY_IN',240,0,'2000-01-01Z','carry-in'),
                       (111,901,3,null,'GRANT',120,0,'2000-01-01Z','future-grant'),
                       (112,901,4,null,'GRANT',480,0,'2000-01-01Z','expired-grant'),
                       (113,42,99,null,'GRANT',999,0,'2000-01-01Z','other-employee')
                """);
    }

    @Test
    void returnsOnlyCurrentBalancesAndExactQueryCutoffInOneReadWithoutChangingRows() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        JsonNode response = response(URL);
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("success", "error", "data");
        assertThat(response.get("success").asBoolean()).isTrue();
        assertThat(response.get("error").isNull()).isTrue();
        JsonNode summary = response.get("data");
        assertThat(summary.propertyNames()).containsExactlyInAnyOrder("asOf", "balances");
        assertThat(summary.get("balances")).isEqualTo(objectMapper.readTree("""
                [{"accountId":902,"balanceTypeId":10,"balanceTypeName":"보상","quantityMinutes":240,"reservedMinutes":0,"remainingMinutes":240},
                 {"accountId":901,"balanceTypeId":20,"balanceTypeName":"연차","quantityMinutes":300,"reservedMinutes":60,"remainingMinutes":240}]
                """));
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).findBalancesByEmployeeId(eq(42L), cutoff.capture());
        assertThat(OffsetDateTime.parse(summary.get("asOf").asString()).toInstant()).isEqualTo(cutoff.getValue().toInstant());
        assertThat(cutoff.getValue().getNano() % 1000).isZero();
        assertThat(SqlCapture.statements.get()).singleElement().satisfies(sql ->
                assertThat(sql).startsWith("with calculation_time").doesNotContain("for update", "for share", "pg_advisory"));
        assertThat(SqlCapture.readOnlyFlags.get()).containsExactly(true);
        assertThat(SqlCapture.isolationLevels.get()).containsExactly((Integer) null);
        assertThat(storedRows()).isEqualTo(before);
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void singleBalancePreservesExistingSixFields() throws Exception {
        jdbcTemplate.update("delete from leave_ledger where account_id=902");
        jdbcTemplate.update("delete from leave_grant where account_id=902");
        jdbcTemplate.update("delete from leave_account where account_id=902");
        JsonNode balances = response(URL).get("data").get("balances");
        assertThat(balances.size()).isEqualTo(1);
        assertThat(balances.get(0).propertyNames()).containsExactlyInAnyOrder(
                "accountId", "balanceTypeId", "balanceTypeName", "quantityMinutes", "reservedMinutes", "remainingMinutes");
    }

    @Test
    void noAccountReturnsTimestampAndEmptyBalances() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenReturn(12345L);
        JsonNode summary = response(URL).get("data");
        assertThat(summary.get("asOf").isString()).isTrue();
        assertThat(summary.get("balances").isEmpty()).isTrue();
    }

    @Test
    void accountWithoutLedgerReturnsZeroInsteadOfOriginalMinutes() throws Exception {
        jdbcTemplate.update("delete from leave_ledger where account_id=901");
        JsonNode balance = response(URL).get("data").get("balances").get(1);
        assertThat(balance.get("quantityMinutes").asLong()).isZero();
        assertThat(balance.get("reservedMinutes").asLong()).isZero();
        assertThat(balance.get("remainingMinutes").asLong()).isZero();
    }

    @Test
    void summaryUserAndAdminBalancesMatchAtSameCutoffAndSnapshot() {
        try (MockedStatic<OffsetDateTime> time = mockStatic(OffsetDateTime.class, CALLS_REAL_METHODS)) {
            time.when(OffsetDateTime::now).thenReturn(AS_OF);
            time.clearInvocations();
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setReadOnly(true);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.executeWithoutResult(status -> {
                try {
                    JsonNode summary = response(URL).get("data");
                    assertThat(response("/api/v1/leaves/me/balances").get("data")).isEqualTo(summary.get("balances"));
                    when(employeeAccess.currentEmployeeId()).thenReturn(7L);
                    when(employeeAccess.isAdmin(7L)).thenReturn(true);
                    assertThat(response("/api/v1/leaves/admin/employees/42/balances").get("data")).isEqualTo(summary.get("balances"));
                    assertThat(OffsetDateTime.parse(summary.get("asOf").asString()).toInstant()).isEqualTo(AS_OF.toInstant());
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
            time.verify(OffsetDateTime::now, times(3));
        }
    }

    @Test
    void exactReturnedCutoffControlsGrantAndLedgerBoundaries() throws Exception {
        jdbcTemplate.update("update leave_grant set valid_from=?,expires_on=? where grant_id=1", AS_OF, AS_OF.plusNanos(1000));
        jdbcTemplate.update("""
                insert into leave_ledger (entry_id,account_id,grant_id,event_type,quantity_delta,created_at,idempotency_key)
                values (114,901,1,'ADJUST',10,?,'exact-cutoff'),(115,901,1,'ADJUST',100,?,'after-cutoff')
                """, AS_OF, AS_OF.plusNanos(1000));
        try (MockedStatic<OffsetDateTime> time = mockStatic(OffsetDateTime.class, CALLS_REAL_METHODS)) {
            time.when(OffsetDateTime::now).thenReturn(AS_OF);
            JsonNode first = response(URL).get("data");
            assertThat(OffsetDateTime.parse(first.get("asOf").asString()).toInstant()).isEqualTo(AS_OF.toInstant());
            assertThat(first.get("balances").get(1).get("quantityMinutes").asLong()).isEqualTo(310);
            time.when(OffsetDateTime::now).thenReturn(AS_OF.plusNanos(1000));
            JsonNode expired = response(URL).get("data");
            assertThat(expired.get("balances").get(1).get("quantityMinutes").asLong()).isZero();
            assertThat(expired.get("balances").get(1).get("reservedMinutes").asLong()).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"-301,0", "0,-61", "0,241"})
    void invalidGrantUsesExisting409WithoutPartialSummary(int quantity, int reserved) throws Exception {
        jdbcTemplate.update("""
                insert into leave_ledger (account_id,grant_id,event_type,quantity_delta,reserved_delta,created_at,idempotency_key)
                values (901,1,'ADJUST',?,?,'2000-01-01Z','invalid-delta')
                """, quantity, reserved);
        mvc.perform(get(URL)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void clientCannotSelectEmployeeAccountOrHistoricalCutoff() throws Exception {
        MvcResult result = mvc.perform(get(URL).param("employeeId", "84").param("asOf", "9999-01-01Z")
                        .header("X-Employee-Id", "84").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":84,\"accountId\":42}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode summary = objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
        assertThat(summary.get("balances").get(0).get("accountId").asLong()).isEqualTo(902);
        assertThat(summary.get("balances").get(1).get("accountId").asLong()).isEqualTo(901);
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).findBalancesByEmployeeId(eq(42L), cutoff.capture());
        assertThat(cutoff.getValue().getYear()).isNotEqualTo(9999);
    }

    @Test
    void concurrentInsertPreservesStatementSnapshotAndNextSummaryReflectsCommit() throws Exception {
        jdbcTemplate.execute("""
                create or replace function summary_test_gate() returns boolean language plpgsql volatile as $$
                begin perform pg_advisory_xact_lock(93425421); return true; end $$
                """);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            writer.setAutoCommit(false);
            writer.createStatement().execute("select pg_advisory_xact_lock(93425421)");
            Future<JsonNode> pending = executor.submit(() -> {
                SqlCapture.gateQuery.set(true);
                try {
                    return response(URL);
                } finally {
                    SqlCapture.gateQuery.remove();
                }
            });
            awaitGateWait();
            writer.createStatement().executeUpdate("""
                    insert into leave_ledger (account_id,grant_id,event_type,quantity_delta,reserved_delta,created_at,idempotency_key)
                    values (901,1,'RESERVE',0,120,'2000-01-01Z','concurrent-reserve'),
                           (901,1,'CONSUME',-120,-120,'2000-01-01Z','concurrent-consume')
                    """);
            writer.commit();
            assertThat(pending.get(10, TimeUnit.SECONDS).get("data").get("balances").get(1).get("quantityMinutes").asLong()).isEqualTo(300);
            JsonNode afterCommit = response(URL).get("data").get("balances").get(1);
            assertThat(afterCommit.get("quantityMinutes").asLong()).isEqualTo(180);
            assertThat(afterCommit.get("reservedMinutes").asLong()).isEqualTo(60);
            assertThat(afterCommit.get("remainingMinutes").asLong()).isEqualTo(120);
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitGateWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbcTemplate.execute("select pg_stat_clear_snapshot()");
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity where wait_event_type='Lock' and wait_event='advisory'
                        and query like '%summary_test_gate()%' and pid<>pg_backend_pid()
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        fail("요약 SELECT가 문장 스냅샷 생성 후 테스트 게이트에서 대기해야 한다.");
    }

    private JsonNode response(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }
}
