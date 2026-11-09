package com.ssafy.leaveservice.domain.ledger;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.ledger.entity.LedgerEventType;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=MyLeaveLedgerPostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.ledger.MyLeaveLedgerPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class MyLeaveLedgerPostgresIntegrationTest {

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<List<Boolean>> readOnlyFlags = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<List<Integer>> isolationLevels = ThreadLocal.withInitial(ArrayList::new);

        public SqlCapture() {
        }

        @Override
        public String inspect(String sql) {
            statements.get().add(sql.toLowerCase());
            readOnlyFlags.get().add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            isolationLevels.get().add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return sql;
        }
    }

    private static final String URL = "/api/v1/leaves/me/ledger";
    private static final List<String> TABLES = List.of(
            "leave_ledger", "leave_request", "leave_grant", "leave_accrual_rule", "leave_policy_type_rule",
            "employee_leave_policy_assignment", "leave_account", "leave_type", "leave_balance_type",
            "leave_policy_version", "leave_policy_group"
    );

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        SqlCapture.statements.get().clear();
        SqlCapture.readOnlyFlags.get().clear();
        SqlCapture.isolationLevels.get().clear();
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : TABLES) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id,name,is_active) values (1,'연차',false),(2,'보상',true)");
        // 계정 ID와 직원 ID를 다르게 두어 소유 직원 조건을 검증한다.
        jdbcTemplate.update("insert into leave_account (account_id,emp_id,balance_type_id) values (901,42,1),(902,42,2),(42,84,1)");
        jdbcTemplate.update("""
                insert into leave_type (leave_type_id,balance_type_id,name,balance_mode,request_unit,is_active)
                values (1,1,'연차 종일','ACCOUNT','DAY',true),(2,1,'연차 반일','ACCOUNT','HALF_DAY',false)
                """);
        jdbcTemplate.update("insert into leave_policy_group (policy_id,name) values (1,'기존 정책'),(2,'새 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id,policy_id,version_no,base_type)
                values (11,1,1,'HIRE_DATE'),(12,1,2,'HIRE_DATE'),(21,2,1,'HIRE_DATE')
                """);
        jdbcTemplate.update("""
                insert into leave_accrual_rule (rule_id,policy_version_id,frequency,grant_minutes,is_active)
                values (51,11,'YEARLY',480,false)
                """);
        jdbcTemplate.update("""
                insert into leave_policy_type_rule (policy_version_id,leave_type_id,allowed_units)
                values (11,1,'FULL'),(12,1,'FULL'),(21,1,'FULL')
                """);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=21 where policy_id=2");
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (assignment_id,emp_id,policy_id,effective_from)
                values (31,42,1,'2000-01-01T00:00:00Z')
                """);
        jdbcTemplate.update("""
                insert into leave_request (leave_req_id,policy_version_id,leave_type_id,emp_id,status)
                values (71,11,1,42,'CANCELLED')
                """);
        jdbcTemplate.update("""
                insert into leave_grant (grant_id,account_id,policy_version_id,rule_id,grant_kind,
                                         granted_on,original_minutes,valid_from,expires_on)
                values (5,901,11,51,'AUTO','2020-01-01T00:00:00Z',7200,'2020-01-01Z','2021-01-01Z'),
                       (8,901,null,null,'MANUAL','2022-01-01Z',30,'3000-01-01Z',null),
                       (12,902,11,null,'COMP','2025-01-01Z',60,'2025-01-01Z','3000-01-01Z'),
                       (99,42,null,null,'MANUAL','2099-01-01Z',999,'2099-01-01Z',null)
                """);
        jdbcTemplate.update("""
                insert into leave_ledger (entry_id,account_id,grant_id,leave_req_id,reverses_entry_id,event_type,
                                          quantity_delta,reserved_delta,reason,created_at,idempotency_key)
                values (101,901,5,null,null,'GRANT',7200,0,null,'2020-01-01Z','ledger-101'),
                       (102,901,8,null,null,'ADJUST',-15,0,null,'2021-01-01Z','ledger-102'),
                       (103,901,8,71,null,'RESERVE',0,480,null,'2022-01-01Z','ledger-103'),
                       (104,901,8,71,null,'RELEASE',0,-480,null,'2023-01-01Z','ledger-104'),
                       (105,901,5,71,null,'CONSUME',-7200,0,null,'2024-01-01Z','ledger-105'),
                       (106,901,8,71,102,'RESTORE',15,0,'  조정 복원  ','2025-01-01T09:00:00+09:00','ledger-106'),
                       (107,902,12,null,null,'EXPIRE',-60,0,null,'2026-01-01Z','ledger-107'),
                       (108,902,12,null,null,'REVOKE',-30,0,null,'2027-01-01Z','ledger-108'),
                       (109,902,12,null,null,'CARRY_OUT',-120,0,null,'2027-01-01Z','ledger-109'),
                       (110,902,12,null,null,'CARRY_IN',120,0,null,'2028-01-01Z','ledger-110'),
                       (999,42,99,null,null,'GRANT',999,0,null,'2099-01-01Z','other-employee')
                """);
    }

    @Test
    void returnsAllTenEventTypesAcrossOwnedAccountsInCreatedAtAndIdDescendingOrder() throws Exception {
        JsonNode response = getResponse();
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("success", "error", "data");
        assertThat(response.get("success").asBoolean()).isTrue();
        assertThat(response.get("error").isNull()).isTrue();
        JsonNode entries = response.get("data");
        assertThat(entryIds(entries)).containsExactly(110L, 109L, 108L, 107L, 106L, 105L, 104L, 103L, 102L, 101L);
        List<LedgerEventType> eventTypes = new ArrayList<>();
        for (JsonNode ledgerEntry : entries) {
            eventTypes.add(LedgerEventType.valueOf(ledgerEntry.get("eventType").asString()));
        }
        assertThat(eventTypes).containsExactlyInAnyOrder(LedgerEventType.values());
        assertThat(entries.get(0).get("accountId").asLong()).isEqualTo(902);
        assertThat(entries.get(0).get("balanceTypeId").asLong()).isEqualTo(2);
        assertThat(entries.get(0).get("balanceTypeName").asString()).isEqualTo("보상");
        assertThat(entries.get(9).get("accountId").asLong()).isEqualTo(901);
        assertThat(entries.get(9).get("balanceTypeId").asLong()).isEqualTo(1);
        assertThat(entries.get(9).get("balanceTypeName").asString()).isEqualTo("연차");
        assertThat(entries.get(5).get("quantityDelta").asInt()).isEqualTo(-7200);
        assertThat(entries.get(6).get("quantityDelta").asInt()).isZero();
        assertThat(entries.get(6).get("reservedDelta").asInt()).isEqualTo(-480);
        assertThat(entries.get(7).get("quantityDelta").asInt()).isZero();
        assertThat(entries.get(7).get("reservedDelta").asInt()).isEqualTo(480);
        assertThat(entries.get(9).get("quantityDelta").asInt()).isEqualTo(7200);
        assertThat(entries.get(9).get("grantId").asLong()).isEqualTo(5);
        assertThat(entries.get(9).get("leaveReqId").isNull()).isTrue();
        assertThat(entries.get(9).get("reversesEntryId").isNull()).isTrue();
        assertThat(entries.get(9).get("reason").isNull()).isTrue();
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void mapsEveryStoredFieldIncludingReversalReasonIdempotencyKeyAndTimestamp() throws Exception {
        JsonNode ledgerEntry = getResponse().get("data").get(4);
        assertThat(ledgerEntry.propertyNames()).containsExactlyInAnyOrder(
                "entryId", "accountId", "balanceTypeId", "balanceTypeName", "grantId", "leaveReqId", "reversesEntryId",
                "eventType", "quantityDelta", "reservedDelta", "reason", "createdAt", "idempotencyKey"
        );
        assertThat(ledgerEntry).isEqualTo(objectMapper.readTree("""
                {"entryId":106,"accountId":901,"balanceTypeId":1,"balanceTypeName":"연차","grantId":8,
                 "leaveReqId":71,"reversesEntryId":102,"eventType":"RESTORE","quantityDelta":15,"reservedDelta":0,
                 "reason":"  조정 복원  ","createdAt":"2025-01-01T00:00:00Z","idempotencyKey":"ledger-106"}
                """));
        assertThat(OffsetDateTime.parse(ledgerEntry.get("createdAt").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2025-01-01T09:00:00+09:00").toInstant());
    }

    @Test
    void executesOneReadOnlySelectWithoutGrantPolicyOrLockQueries() throws Exception {
        getResponse();
        assertThat(SqlCapture.statements.get()).singleElement().satisfies(sql ->
                assertThat(sql).startsWith("select ").contains("leave_ledger", "join leave_account", "join leave_balance_type")
                        .doesNotContain("join leave_grant", "join leave_type ", "join leave_policy", "employee_leave_policy_assignment",
                                "for update", "for share", "pg_advisory"));
        assertThat(SqlCapture.readOnlyFlags.get()).containsExactly(true);
        assertThat(SqlCapture.isolationLevels.get()).containsExactly((Integer) null);
    }

    @Test
    void preservesAllStoredRowsAndXminIncludingAccountsGrantsAndLedger() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        getResponse();
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void returnsOneHistoricalEntryWithoutAssignmentOrActiveVersion() throws Exception {
        jdbcTemplate.update("delete from employee_leave_policy_assignment");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        jdbcTemplate.update("delete from leave_ledger where entry_id<>101");
        assertThat(entryIds(getResponse().get("data"))).containsExactly(101L);
    }

    @ParameterizedTest
    @ValueSource(longs = {42, 12345})
    void returnsEmptyListForAccountsWithoutLedgerOrEmployeeWithoutAccount(long employeeId) throws Exception {
        jdbcTemplate.update("delete from leave_ledger where account_id in (901,902)");
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(content().json(
                "{\"success\":true,\"error\":null,\"data\":[]}", JsonCompareMode.STRICT));
    }

    @Test
    void queryHeadersAndBodyCannotSelectAnotherEmployeeOrAccount() throws Exception {
        MvcResult result = mvc.perform(get(URL).param("employeeId", "84").param("accountId", "42")
                        .header("X-Employee-Id", "84").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":84,\"accountId\":42}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(entryIds(objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data")))
                .containsExactly(110L, 109L, 108L, 107L, 106L, 105L, 104L, 103L, 102L, 101L);
        verify(employeeAccess, times(1)).currentEmployeeId();
    }

    @Test
    void policyReassignmentAndActivationPreserveLedgerAndGrantHistory() throws Exception {
        JsonNode before = getResponse();
        List<Map<String, Object>> ledgerBefore = jdbcTemplate.queryForList("select *,xmin::text as row_version from leave_ledger order by entry_id");
        List<Map<String, Object>> grantsBefore = jdbcTemplate.queryForList("select *,xmin::text as row_version from leave_grant order by grant_id");
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        mvc.perform(put("/api/v1/leaves/admin/policies/1/active-version")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"versionId\":12}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/leaves/admin/employees/42/policy-assignments")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"policyId\":2}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/leaves/me/policy")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyId").value(2));
        assertThat(getResponse()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForList("select *,xmin::text as row_version from leave_ledger order by entry_id")).isEqualTo(ledgerBefore);
        assertThat(jdbcTemplate.queryForList("select *,xmin::text as row_version from leave_grant order by grant_id")).isEqualTo(grantsBefore);
    }

    @Test
    void usesCurrentBalanceNameWithoutInferringLeaveTypeOrFilteringInactiveMasters() throws Exception {
        jdbcTemplate.update("update leave_balance_type set name='변경된 잔액명' where balance_type_id=1");
        JsonNode entries = getResponse().get("data");
        assertThat(entries.size()).isEqualTo(10);
        assertThat(entries.get(9).get("balanceTypeName").asString()).isEqualTo("변경된 잔액명");
        assertThat(entries.get(9).has("leaveTypeId")).isFalse();
    }

    @Test
    void mapsDatabaseCreatedAtDefaultAndKeepsZeroDeltaWhenOtherDeltaIsNonzero() throws Exception {
        jdbcTemplate.update("""
                insert into leave_ledger (entry_id,account_id,grant_id,event_type,reserved_delta,idempotency_key)
                values (1000,901,8,'RESERVE',5,'default-created-at')
                """);
        JsonNode entries = getResponse().get("data");
        JsonNode insertedEntry = null;
        for (JsonNode ledgerEntry : entries) {
            if (ledgerEntry.get("entryId").asLong() == 1000) {
                insertedEntry = ledgerEntry;
            }
        }
        assertThat(insertedEntry).isNotNull();
        assertThat(insertedEntry.get("quantityDelta").asInt()).isZero();
        assertThat(insertedEntry.get("reservedDelta").asInt()).isEqualTo(5);
        OffsetDateTime storedCreatedAt = jdbcTemplate.queryForObject(
                "select created_at from leave_ledger where entry_id=1000", OffsetDateTime.class);
        assertThat(OffsetDateTime.parse(insertedEntry.get("createdAt").asString()).toInstant()).isEqualTo(storedCreatedAt.toInstant());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update leave_ledger set account_id=99999 where entry_id=101",
            "update leave_ledger set account_id=902 where entry_id=101",
            "update leave_ledger set grant_id=99 where entry_id=101",
            "update leave_ledger set grant_id=99999 where entry_id=101",
            "update leave_ledger set grant_id=null where entry_id=101",
            "update leave_ledger set event_type='UNKNOWN' where entry_id=101",
            "update leave_ledger set quantity_delta=0,reserved_delta=0 where entry_id=101",
            "update leave_ledger set leave_req_id=99999 where entry_id=101",
            "update leave_ledger set reverses_entry_id=99999 where entry_id=101",
            "update leave_ledger set reverses_entry_id=102 where entry_id=104",
            "update leave_ledger set idempotency_key='ledger-102' where entry_id=101",
            "update leave_ledger set reason=repeat('가',501) where entry_id=101",
            "update leave_ledger set idempotency_key=repeat('k',121) where entry_id=101"
    })
    void actualFkUniqueCheckAndLengthConstraintsRejectInvalidLedger(String sql) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_ledger", Integer.class)).isEqualTo(11);
    }

    private JsonNode getResponse() throws Exception {
        MvcResult result = mvc.perform(get(URL)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private List<Long> entryIds(JsonNode entries) {
        List<Long> entryIds = new ArrayList<>();
        for (JsonNode ledgerEntry : entries) {
            entryIds.add(ledgerEntry.get("entryId").asLong());
        }
        return entryIds;
    }

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }
}
