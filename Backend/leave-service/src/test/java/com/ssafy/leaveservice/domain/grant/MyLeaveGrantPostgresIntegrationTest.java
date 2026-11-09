package com.ssafy.leaveservice.domain.grant;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
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
        "leave.test.database-owner=MyLeaveGrantPostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.grant.MyLeaveGrantPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class MyLeaveGrantPostgresIntegrationTest {

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

    private static final String URL = "/api/v1/leaves/me/grants";
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
                insert into leave_grant (grant_id,account_id,policy_version_id,rule_id,parent_grant_id,grant_kind,
                                         granted_on,original_minutes,valid_from,expires_on,created_at)
                values (5,901,11,51,null,'AUTO','2020-01-01T09:00:00+09:00',7200,'2020-02-01Z','2021-01-01Z','2020-01-01Z'),
                       (8,901,null,null,null,'MANUAL','2022-01-01Z',480,'3000-01-01Z',null,'2022-02-01Z'),
                       (9,901,11,null,5,'CARRY_OVER','2022-01-01Z',120,'2022-01-01Z','2023-01-01Z','2022-01-01Z'),
                       (12,902,null,null,null,'COMP','2025-01-01Z',60,'2025-01-01Z','3000-01-01Z','2025-01-01Z'),
                       (13,902,21,null,null,'MANUAL','2026-01-01Z',90,'3000-01-01Z',null,'2026-01-01Z'),
                       (99,42,null,null,null,'MANUAL','2099-01-01Z',999,'2099-01-01Z',null,'2099-01-01Z')
                """);
        jdbcTemplate.update("""
                insert into leave_ledger (entry_id,account_id,grant_id,event_type,quantity_delta,reserved_delta,idempotency_key)
                values (100,901,5,'GRANT',7200,0,'grant-5'),(101,901,5,'CONSUME',-7200,0,'consume-5'),
                       (102,901,9,'GRANT',120,0,'grant-9'),(103,901,9,'CONSUME',-60,0,'consume-9'),
                       (104,902,12,'GRANT',60,0,'grant-12'),(105,902,12,'RESERVE',0,60,'reserve-12')
                """);
    }

    @Test
    void returnsAllKindsAndOriginalQuantitiesInGrantTimeAndIdDescendingOrder() throws Exception {
        JsonNode response = getResponse();
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("success", "error", "data");
        assertThat(response.get("success").asBoolean()).isTrue();
        assertThat(response.get("error").isNull()).isTrue();
        JsonNode grants = response.get("data");
        assertThat(grantIds(grants)).containsExactly(13L, 12L, 9L, 8L, 5L);
        assertThat(grants.get(0).propertyNames()).containsExactlyInAnyOrder(
                "grantId", "accountId", "balanceTypeId", "balanceTypeName", "policyVersionId", "ruleId",
                "parentGrantId", "grantKind", "grantedOn", "originalMinutes", "validFrom", "expiresOn", "createdAt"
        );
        assertThat(grants.get(0).get("accountId").asLong()).isEqualTo(902);
        assertThat(grants.get(0).get("balanceTypeId").asLong()).isEqualTo(2);
        assertThat(grants.get(0).get("balanceTypeName").asString()).isEqualTo("보상");
        assertThat(grants.get(1).get("grantKind").asString()).isEqualTo("COMP");
        assertThat(grants.get(2).get("grantKind").asString()).isEqualTo("CARRY_OVER");
        assertThat(grants.get(2).get("parentGrantId").asLong()).isEqualTo(5);
        assertThat(grants.get(2).get("originalMinutes").asInt()).isEqualTo(120);
        assertThat(grants.get(3).get("grantKind").asString()).isEqualTo("MANUAL");
        assertThat(grants.get(3).get("policyVersionId").isNull()).isTrue();
        assertThat(grants.get(3).get("ruleId").isNull()).isTrue();
        assertThat(grants.get(3).get("expiresOn").isNull()).isTrue();
        assertThat(grants.get(4).get("grantKind").asString()).isEqualTo("AUTO");
        assertThat(grants.get(4).get("originalMinutes").asInt()).isEqualTo(7200);
        assertThat(grants.get(4).get("policyVersionId").asLong()).isEqualTo(11);
        assertThat(grants.get(4).get("ruleId").asLong()).isEqualTo(51);
        assertThat(grants.get(4).get("balanceTypeName").asString()).isEqualTo("연차");
        assertThat(OffsetDateTime.parse(grants.get(4).get("grantedOn").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2020-01-01T09:00:00+09:00").toInstant());
        assertThat(OffsetDateTime.parse(grants.get(4).get("validFrom").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2020-02-01T00:00:00Z").toInstant());
        assertThat(OffsetDateTime.parse(grants.get(4).get("expiresOn").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2021-01-01T00:00:00Z").toInstant());
        assertThat(OffsetDateTime.parse(grants.get(4).get("createdAt").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2020-01-01T00:00:00Z").toInstant());
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void executesOneFetchJoinedReadOnlySelectWithoutPolicyLedgerOrLocks() throws Exception {
        getResponse();
        assertThat(SqlCapture.statements.get()).singleElement().satisfies(sql ->
                assertThat(sql).startsWith("select ").contains("leave_grant", "join leave_account", "join leave_balance_type")
                        .doesNotContain("join leave_type ", "join leave_policy", "leave_ledger", "for update", "for share", "pg_advisory"));
        assertThat(SqlCapture.readOnlyFlags.get()).containsExactly(true);
        assertThat(SqlCapture.isolationLevels.get()).containsExactly((Integer) null);
    }

    @Test
    void leavesAllStoredRowsAndGrantAndLedgerXminUnchanged() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        getResponse();
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void returnsOneGrantWithoutCurrentAssignmentOrActiveVersion() throws Exception {
        jdbcTemplate.update("delete from employee_leave_policy_assignment");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        jdbcTemplate.update("delete from leave_ledger");
        jdbcTemplate.update("delete from leave_grant where grant_id<>5");
        JsonNode grants = getResponse().get("data");
        assertThat(grantIds(grants)).containsExactly(5L);
        assertThat(grants.get(0).get("ruleId").asLong()).isEqualTo(51);
    }

    @ParameterizedTest
    @ValueSource(longs = {42, 12345})
    void returnsEmptyListForAccountWithoutGrantsOrEmployeeWithoutAccount(long employeeId) throws Exception {
        jdbcTemplate.update("delete from leave_ledger");
        jdbcTemplate.update("delete from leave_grant where account_id in (901,902)");
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}"));
    }

    @Test
    void ignoresSpoofedEmployeeIdentityInQueryHeadersAndBody() throws Exception {
        MvcResult result = mvc.perform(get(URL).param("employeeId", "84").header("X-Employee-Id", "84")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"employeeId\":84}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(grantIds(objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data")))
                .containsExactly(13L, 12L, 9L, 8L, 5L);
        verify(employeeAccess, times(1)).currentEmployeeId();
    }

    @Test
    void changingCurrentPolicyAndActiveVersionDoesNotRewritePastGrants() throws Exception {
        JsonNode before = getResponse();
        List<Map<String, Object>> grantsBefore = jdbcTemplate.queryForList("select *,xmin::text as row_version from leave_grant order by grant_id");
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        mvc.perform(put("/api/v1/leaves/admin/policies/1/active-version")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"versionId\":12}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/leaves/admin/employees/42/policy-assignments")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"policyId\":2,\"effectiveFrom\":\"2050-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated());
        assertThat(getResponse()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForList("select *,xmin::text as row_version from leave_grant order by grant_id"))
                .isEqualTo(grantsBefore);
    }

    @Test
    void returnsCurrentBalanceNameWithoutInferringSharedLeaveTypes() throws Exception {
        jdbcTemplate.update("update leave_balance_type set name='변경된 잔액명' where balance_type_id=1");
        JsonNode grants = getResponse().get("data");
        assertThat(grants.size()).isEqualTo(5);
        assertThat(grants.get(4).get("balanceTypeName").asString()).isEqualTo("변경된 잔액명");
        assertThat(grants.get(4).has("leaveTypeId")).isFalse();
        assertThat(grants.get(4).has("leaveTypeName")).isFalse();
    }

    @Test
    void mapsDatabaseTimestampDefaultsWithoutRelyingOnApplicationClock() throws Exception {
        jdbcTemplate.update("""
                insert into leave_grant (grant_id,account_id,grant_kind,original_minutes,valid_from)
                values (1000,901,'MANUAL',30,'3000-01-01Z')
                """);
        JsonNode grantRecord = getResponse().get("data").get(0);
        assertThat(grantRecord.get("grantId").asLong()).isEqualTo(1000);
        OffsetDateTime grantedOn = OffsetDateTime.parse(grantRecord.get("grantedOn").asString());
        OffsetDateTime createdAt = OffsetDateTime.parse(grantRecord.get("createdAt").asString());
        assertThat(grantedOn.toInstant()).isEqualTo(createdAt.toInstant());
        OffsetDateTime storedGrantedOn = jdbcTemplate.queryForObject(
                "select granted_on from leave_grant where grant_id=1000", OffsetDateTime.class);
        assertThat(grantedOn.toInstant()).isEqualTo(storedGrantedOn.toInstant());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update leave_grant set account_id=99999 where grant_id=5",
            "update leave_grant set grant_kind='UNKNOWN' where grant_id=5",
            "update leave_grant set original_minutes=0 where grant_id=5",
            "update leave_grant set expires_on=valid_from where grant_id=5",
            "update leave_grant set policy_version_id=null where grant_id=5",
            "update leave_grant set policy_version_id=12 where grant_id=5",
            "update leave_grant set parent_grant_id=99999 where grant_id=5",
            "update leave_ledger set account_id=902 where entry_id=100"
    })
    void actualDatabaseConstraintsRejectInconsistentHistory(String sql) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_grant", Integer.class)).isEqualTo(6);
    }

    private JsonNode getResponse() throws Exception {
        MvcResult result = mvc.perform(get(URL)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private List<Long> grantIds(JsonNode grants) {
        List<Long> grantIds = new ArrayList<>();
        for (JsonNode grantRecord : grants) {
            grantIds.add(grantRecord.get("grantId").asLong());
        }
        return grantIds;
    }

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }
}
