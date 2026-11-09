package com.ssafy.leaveservice.domain.account;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.LeaveAccountBalance;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=MyLeaveBalancePostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.account.MyLeaveBalancePostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class MyLeaveBalancePostgresIntegrationTest {

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
            // 동시성 테스트에서만 문장 스냅샷 생성 후 실행을 멈춘다. 운영 쿼리에는 없는 테스트 함수다.
            if (gateQuery.get() && sql.contains("with calculation_time")) {
                gateQuery.remove();
                return sql.replace("where emp_id = ?", "where emp_id = ? and balance_test_gate()");
            }
            return sql;
        }
    }

    private static final String URL = "/api/v1/leaves/me/balances";
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
    @Autowired private LeaveAccountRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private EmployeeAccess employeeAccess;

    private MockMvc mvc;
    private int ledgerSequence;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        SqlCapture.statements.get().clear();
        SqlCapture.readOnlyFlags.get().clear();
        SqlCapture.isolationLevels.get().clear();
        SqlCapture.gateQuery.remove();
        ledgerSequence = 0;
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : TABLES) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id,name,is_active) values (20,'연차',true),(10,'보상',false)");
        jdbcTemplate.update("insert into leave_account (account_id,emp_id,balance_type_id) values (901,42,20),(902,42,10),(42,84,10)");
        addGrant(1, 901, "2000-01-01T00:00:00Z", "3000-01-01T00:00:00Z");
        addGrant(2, 902, "2000-01-01T00:00:00Z", null);
        addGrant(99, 42, "2000-01-01T00:00:00Z", null);
        addLedger(1, 901, "GRANT", 480, 0);
        addLedger(2, 902, "GRANT", 120, 0);
        addLedger(99, 42, "GRANT", 999, 0);
    }

    @Test
    void returnsOwnedBalancesInTypeOrderIncludingInactiveMasterWithoutDoubleCountingOriginal() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(content().json("""
                {"success":true,"error":null,"data":[
                 {"accountId":902,"balanceTypeId":10,"balanceTypeName":"보상","quantityMinutes":120,"reservedMinutes":0,"remainingMinutes":120},
                 {"accountId":901,"balanceTypeId":20,"balanceTypeName":"연차","quantityMinutes":480,"reservedMinutes":0,"remainingMinutes":480}]}
                """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void noAccountsReturnsEmptyApiResult() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenReturn(12345L);
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void accountsWithoutGrantsOrWithoutLedgerReturnZeroWithoutOriginalFallback() throws Exception {
        jdbcTemplate.update("delete from leave_ledger where account_id in (901,902)");
        jdbcTemplate.update("delete from leave_grant where grant_id=1");
        JsonNode balances = getResponse().get("data");
        assertThat(balances.size()).isEqualTo(2);
        for (JsonNode balance : balances) {
            assertThat(balance.get("quantityMinutes").asLong()).isZero();
            assertThat(balance.get("reservedMinutes").asLong()).isZero();
            assertThat(balance.get("remainingMinutes").asLong()).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "GRANT,120,0,0,0,600,0,600",
            "RESERVE,0,240,0,0,480,240,240",
            "RELEASE,0,-120,0,240,480,120,360",
            "CONSUME,-240,-240,0,240,240,0,240",
            "RESTORE,120,0,-120,0,480,0,480",
            "ADJUST,50,0,0,0,530,0,530",
            "ADJUST,-50,0,0,0,430,0,430",
            "REVOKE,-60,0,0,0,420,0,420",
            "EXPIRE,-60,0,0,0,420,0,420",
            "CARRY_OUT,-60,0,0,0,420,0,420",
            "CARRY_IN,60,0,0,0,540,0,540"
    })
    void sumsStoredDeltasForEveryEventWithoutTypeBasedAdjustment(
            String eventType, int quantity, int reserved, int priorQuantity, int priorReserved,
            long expectedQuantity, long expectedReserved, long expectedRemaining
    ) throws Exception {
        if (priorQuantity != 0 || priorReserved != 0) {
            addLedger(1, 901, "ADJUST", priorQuantity, priorReserved);
        }
        addLedger(1, 901, eventType, quantity, reserved);
        assertBalance(getResponse().get("data").get(1), expectedQuantity, expectedReserved, expectedRemaining);
    }

    @Test
    void aggregatesEachGrantOnceWithoutMixingAccountsOrMultiplyingByLeaveTypes() throws Exception {
        jdbcTemplate.update("""
                insert into leave_type (leave_type_id,balance_type_id,name,balance_mode,request_unit)
                values (1,20,'연차 종일','ACCOUNT','DAY'),(2,20,'연차 반일','ACCOUNT','HALF_DAY')
                """);
        addGrant(3, 901, "2000-01-01T00:00:00Z", null);
        addLedger(3, 901, "GRANT", 60, 0);
        addLedger(3, 901, "RESERVE", 0, 30);
        JsonNode balances = getResponse().get("data");
        assertBalance(balances.get(0), 120, 0, 120);
        assertBalance(balances.get(1), 540, 30, 510);
    }

    @ParameterizedTest
    @CsvSource({
            "2029-01-01T00:00:00Z,2031-01-01T00:00:00Z,540",
            "2030-01-01T00:00:00Z,2031-01-01T00:00:00Z,540",
            "2030-01-01T00:00:00.000001Z,2031-01-01T00:00:00Z,480",
            "2029-01-01T00:00:00Z,2030-01-01T00:00:00Z,480",
            "2029-01-01T00:00:00Z,2029-12-31T23:59:59.999999Z,480",
            "2029-01-01T09:00:00+09:00,2030-01-01T09:00:00+09:00,480"
    })
    void appliesInclusiveStartAndExclusiveExpiryAtOneExactCutoff(String start, String end, long expectedQuantity) {
        addGrant(3, 901, start, end);
        addLedger(3, 901, "GRANT", 60, 0);
        addLedger(3, 901, "RESERVE", 0, 30);
        LeaveAccountBalance balance = balancesAt(AS_OF).get(1);
        assertThat(balance.getQuantityMinutes()).isEqualByComparingTo(BigDecimal.valueOf(expectedQuantity));
        assertThat(balance.getReservedMinutes()).isEqualByComparingTo(expectedQuantity == 540 ? "30" : "0");
    }

    @ParameterizedTest
    @CsvSource({"2029-12-31T23:59:59.999999Z,540", "2030-01-01T00:00:00Z,540", "2030-01-01T00:00:00.000001Z,480"})
    void onlyIncludesLedgerCreatedAtOrBeforeCutoff(String createdAt, long expectedQuantity) {
        long entryId = addLedger(1, 901, "ADJUST", 60, 0);
        jdbcTemplate.update("update leave_ledger set created_at=cast(? as timestamptz) where entry_id=?", createdAt, entryId);
        assertThat(balancesAt(AS_OF).get(1).getQuantityMinutes()).isEqualByComparingTo(BigDecimal.valueOf(expectedQuantity));
    }

    @Test
    void expiresParentWithoutReaddingItWhenChildCarryInExists() throws Exception {
        jdbcTemplate.update("update leave_grant set expires_on='2001-01-01Z' where grant_id=1");
        addLedger(1, 901, "CARRY_OUT", -120, 0);
        addLedger(1, 901, "EXPIRE", -360, 0);
        addGrant(3, 901, "2000-01-01T00:00:00Z", null);
        jdbcTemplate.update("update leave_grant set parent_grant_id=1 where grant_id=3");
        addLedger(3, 901, "CARRY_IN", 120, 0);
        assertBalance(getResponse().get("data").get(1), 120, 0, 120);
    }

    @Test
    void includesBothReversalOriginalAndOppositeDeltaWithoutReissuingOriginalMinutes() throws Exception {
        long originalEntry = addLedger(1, 901, "ADJUST", -60, 0);
        long reversalEntry = addLedger(1, 901, "RESTORE", 60, 0);
        jdbcTemplate.update("update leave_ledger set reverses_entry_id=? where entry_id=?", originalEntry, reversalEntry);
        assertBalance(getResponse().get("data").get(1), 480, 0, 480);
    }

    @ParameterizedTest
    @CsvSource({"-481,0", "0,-1", "0,481"})
    void invalidValidGrantCannotBeHiddenByAnotherGrant(String quantity, String reserved) throws Exception {
        addLedger(1, 901, "ADJUST", Integer.parseInt(quantity), Integer.parseInt(reserved));
        addGrant(3, 901, "2000-01-01T00:00:00Z", null);
        addLedger(3, 901, "GRANT", 1000, 0);
        addLedger(3, 901, "RESERVE", 0, 100);
        Map<String, List<Map<String, Object>>> before = storedRows();
        mvc.perform(get(URL)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"))
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void invalidExpiredOrFutureGrantDoesNotAffectCurrentBalances() throws Exception {
        addGrant(3, 901, "2000-01-01T00:00:00Z", "2001-01-01T00:00:00Z");
        addGrant(4, 901, "3000-01-01T00:00:00Z", null);
        addLedger(3, 901, "ADJUST", -60, 0);
        addLedger(4, 901, "ADJUST", 0, -60);
        assertBalance(getResponse().get("data").get(1), 480, 0, 480);
    }

    @Test
    void sumsBeyondIntegerRangeExactly() throws Exception {
        addLedger(1, 901, "ADJUST", Integer.MAX_VALUE, 0);
        addLedger(1, 901, "ADJUST", Integer.MAX_VALUE, 0);
        assertBalance(getResponse().get("data").get(1), 4294967774L, 0, 4294967774L);
    }

    @Test
    void oneReadOnlyStatementPreservesAllRowsAndXminWithoutCurrentPolicyOrLocks() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        getResponse();
        assertThat(SqlCapture.statements.get()).singleElement().satisfies(sql ->
                assertThat(sql).startsWith("with calculation_time").contains("sum(cast(ledger.quantity_delta as numeric))")
                        .doesNotContain("original_minutes", "leave_policy", "for update", "for share", "pg_advisory", "leave_balance_snapshot"));
        assertThat(SqlCapture.readOnlyFlags.get()).containsExactly(true);
        assertThat(SqlCapture.isolationLevels.get()).containsExactly((Integer) null);
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void spoofedEmployeeAccountAndCutoffInputsCannotChangeScope() throws Exception {
        MvcResult result = mvc.perform(get(URL).param("employeeId", "84").param("accountId", "42").param("asOf", "9999-01-01Z")
                        .header("X-Employee-Id", "84").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":84,\"accountId\":42}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode balances = objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
        assertThat(balances.size()).isEqualTo(2);
        assertThat(balances.get(0).get("accountId").asLong()).isEqualTo(902);
        assertThat(balances.get(1).get("accountId").asLong()).isEqualTo(901);
        verify(employeeAccess, times(1)).currentEmployeeId();
    }

    @Test
    void currentAssignmentAndActiveVersionChangesDoNotReinterpretStoredBalances() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id,name) values (1,'이전 그룹'),(2,'현재 그룹')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id,policy_id,version_no,base_type)
                values (11,1,1,'HIRE_DATE'),(21,2,1,'HIRE_DATE')
                """);
        jdbcTemplate.update("update leave_grant set policy_version_id=11 where grant_id=1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (assignment_id,emp_id,policy_id,effective_from)
                values (31,42,1,'2000-01-01Z')
                """);
        JsonNode before = getResponse();
        jdbcTemplate.update("update employee_leave_policy_assignment set policy_id=2 where assignment_id=31");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null where policy_id=1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=21 where policy_id=2");
        assertThat(getResponse()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("select policy_version_id from leave_grant where grant_id=1", Long.class)).isEqualTo(11L);
    }

    @Test
    void verifiesActualSumTypesAccountUniquenessCompositeFkAndAbsenceOfSnapshotTable() {
        assertThat(jdbcTemplate.queryForObject("select to_regclass('leave_balance_snapshot')::text", String.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select pg_typeof(sum(quantity_delta))::text from leave_ledger", String.class))
                .isEqualTo("bigint");
        assertThat(jdbcTemplate.queryForObject("select pg_typeof(sum(cast(quantity_delta as numeric)))::text from leave_ledger", String.class))
                .isEqualTo("numeric");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into leave_account (account_id,emp_id,balance_type_id) values (999,42,20)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("update leave_ledger set account_id=902 where grant_id=1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentCommittedLedgerEntriesAreObservedAsOneStatementSnapshot(boolean alsoConsume) throws Exception {
        jdbcTemplate.execute("""
                create or replace function balance_test_gate() returns boolean language plpgsql volatile as $$
                begin perform pg_advisory_xact_lock(91425421); return true; end $$
                """);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            writer.setAutoCommit(false);
            writer.createStatement().execute("select pg_advisory_xact_lock(91425421)");
            Future<JsonNode> pendingResponse = executor.submit(() -> {
                SqlCapture.gateQuery.set(true);
                try {
                    return getResponse();
                } finally {
                    SqlCapture.gateQuery.remove();
                }
            });
            awaitGateWait();
            writer.createStatement().executeUpdate("""
                    insert into leave_ledger (account_id,grant_id,event_type,quantity_delta,reserved_delta,created_at,idempotency_key)
                    values (901,1,'RESERVE',0,240,'2000-01-01Z','concurrent-reserve')
                    """);
            if (alsoConsume) {
                writer.createStatement().executeUpdate("""
                        insert into leave_ledger (account_id,grant_id,event_type,quantity_delta,reserved_delta,created_at,idempotency_key)
                        values (901,1,'CONSUME',-240,-240,'2000-01-01Z','concurrent-consume')
                        """);
            }
            writer.commit();
            assertBalance(pendingResponse.get(10, TimeUnit.SECONDS).get("data").get(1), 480, 0, 480);
            assertBalance(getResponse().get("data").get(1), alsoConsume ? 240 : 480, alsoConsume ? 0 : 240, 240);
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
                        and query like '%balance_test_gate()%' and pid<>pg_backend_pid()
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        fail("잔액 SELECT가 문장 스냅샷 생성 후 테스트 게이트에서 대기해야 한다.");
    }

    private List<LeaveAccountBalance> balancesAt(OffsetDateTime asOf) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        return transaction.execute(status -> repository.findBalancesByEmployeeId(42L, asOf));
    }

    private void addGrant(long grantId, long accountId, String start, String end) {
        jdbcTemplate.update("""
                insert into leave_grant (grant_id,account_id,grant_kind,original_minutes,valid_from,expires_on)
                values (?,?,'MANUAL',9999,cast(? as timestamptz),cast(? as timestamptz))
                """, grantId, accountId, start, end);
    }

    private long addLedger(long grantId, long accountId, String event, int quantity, int reserved) {
        return jdbcTemplate.queryForObject("""
                insert into leave_ledger (account_id,grant_id,event_type,quantity_delta,reserved_delta,created_at,idempotency_key)
                values (?,?,?,?,?,'2000-01-01Z',?) returning entry_id
                """, Long.class, accountId, grantId, event, quantity, reserved, "balance-fixture-" + ++ledgerSequence);
    }

    private JsonNode getResponse() throws Exception {
        MvcResult result = mvc.perform(get(URL)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertBalance(JsonNode balance, long quantity, long reserved, long remaining) {
        assertThat(balance.get("quantityMinutes").asLong()).isEqualTo(quantity);
        assertThat(balance.get("reservedMinutes").asLong()).isEqualTo(reserved);
        assertThat(balance.get("remainingMinutes").asLong()).isEqualTo(remaining);
    }

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }
}
