package com.ssafy.leaveservice.domain.leavetype;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=RequestableLeaveTypePostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.leavetype.RequestableLeaveTypePostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class RequestableLeaveTypePostgresIntegrationTest {

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<List<Boolean>> readOnlyFlags = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<List<Integer>> isolationLevels = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<Runnable> beforeTypesQuery = new ThreadLocal<>();

        public SqlCapture() {
        }

        @Override
        public String inspect(String sql) {
            String normalizedSql = sql.toLowerCase();
            statements.get().add(normalizedSql);
            readOnlyFlags.get().add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            isolationLevels.get().add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            Runnable callback = beforeTypesQuery.get();
            if (callback != null && normalizedSql.contains("from leave_type ") && normalizedSql.contains("leave_policy_type_rule")) {
                beforeTypesQuery.remove();
                callback.run();
            }
            return sql;
        }
    }

    private static final String URL = "/api/v1/leaves/types";

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        SqlCapture.statements.get().clear();
        SqlCapture.readOnlyFlags.get().clear();
        SqlCapture.isolationLevels.get().clear();
        SqlCapture.beforeTypesQuery.remove();
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : List.of("leave_ledger", "leave_request", "leave_grant", "leave_accrual_rule", "leave_policy_type_rule",
                "employee_leave_policy_assignment", "leave_account", "leave_type", "leave_balance_type",
                "leave_policy_version", "leave_policy_group")) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        jdbcTemplate.update("insert into leave_policy_group (policy_id,name) values (1,'일반 정책'),(2,'다른 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id,policy_id,version_no,base_type,effective_from,effective_to)
                values (11,1,1,'HIRE_DATE','2099-01-01T00:00:00Z','2100-01-01T00:00:00Z'),
                       (12,1,2,'HIRE_DATE',null,null),(13,1,3,'HIRE_DATE',null,null),(21,2,1,'HIRE_DATE',null,null)
                """);
        jdbcTemplate.update("""
                insert into leave_type (leave_type_id,name,balance_mode,request_unit,max_minutes,is_active)
                values (7,'규칙 없는 유형','NONE','DAY',null,true),(6,'새 버전 유형','NONE','HOUR',90,true),
                       (5,'신청 불가','NONE','HALF_DAY',240,true),(4,'비활성 유형','NONE','DAY',60,false),
                       (3,'시간 휴가','NONE','HOUR',120,true),(2,'경조휴가','NONE','DAY',null,true),
                       (1,'연차','NONE','MIXED',480,true)
                """);
        jdbcTemplate.update("""
                insert into leave_policy_type_rule
                    (rule_id,policy_version_id,leave_type_id,is_requestable,allowed_units,min_minutes,max_minutes)
                values (3,11,3,true,'HOUR',15,60),(1,11,1,true,'MIXED',60,240),(2,11,2,true,'FULL',null,null),
                       (4,11,4,true,'MIXED',100,1000),(5,11,5,false,'FULL',30,500),
                       (6,12,6,true,'HOUR',30,90),(7,12,2,true,'FULL',null,null),(8,21,7,true,'FULL',null,null)
                """);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=21 where policy_id=2");
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (assignment_id,emp_id,policy_id,effective_from)
                values (31,42,1,'2000-01-01T00:00:00Z'),(81,84,2,'2000-01-01T00:00:00Z')
                """);
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id,name) values (1,'연차')");
        jdbcTemplate.update("insert into leave_account (account_id,emp_id,balance_type_id) values (1,84,1)");
        jdbcTemplate.update("insert into leave_request (leave_req_id,policy_version_id,leave_type_id,emp_id,status) values (1,21,7,84,'CANCELLED')");
        jdbcTemplate.update("""
                insert into leave_grant (grant_id,account_id,policy_version_id,grant_kind,original_minutes,valid_from)
                values (1,1,21,'MANUAL',60,'2020-01-01T00:00:00Z')
                """);
        jdbcTemplate.update("""
                insert into leave_ledger (entry_id,account_id,grant_id,leave_req_id,event_type,quantity_delta,idempotency_key)
                values (1,1,1,1,'GRANT',60,'requestable-history')
                """);
    }

    @Test
    void returnsOnlyActiveRequestableCurrentVersionTypesInIdOrderWithoutCheckingBalance() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(content().json("""
                {"success":true,"error":null,"data":[
                 {"leaveTypeId":1,"leaveTypeName":"연차","allowedUnits":"MIXED","effectiveMinMinutes":60,"effectiveMaxMinutes":240},
                 {"leaveTypeId":2,"leaveTypeName":"경조휴가","allowedUnits":"FULL","effectiveMinMinutes":null,"effectiveMaxMinutes":null},
                 {"leaveTypeId":3,"leaveTypeName":"시간 휴가","allowedUnits":"HOUR","effectiveMinMinutes":15,"effectiveMaxMinutes":60}]}
                """, JsonCompareMode.STRICT));
        verify(employeeAccess, times(1)).currentEmployeeId();
        verify(employeeAccess, never()).isAdmin(anyLong());
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void performsExactlyTwoScalarReadsInOneReadOnlyRepeatableReadTransaction() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk());
        assertThat(SqlCapture.statements.get()).hasSize(2).allSatisfy(sql ->
                assertThat(sql).startsWith("select ").doesNotContain("for update", "for share", "for no key update", "pg_advisory"));
        assertThat(SqlCapture.statements.get().getFirst()).contains("employee_leave_policy_assignment");
        assertThat(SqlCapture.statements.get().getLast()).contains("leave_type", "join leave_policy_type_rule");
        assertThat(SqlCapture.readOnlyFlags.get()).containsExactly(true, true);
        assertThat(SqlCapture.isolationLevels.get()).containsExactly(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_REPEATABLE_READ);
    }

    @Test
    void ignoresSpoofedEmployeeIdFromQueryHeaderAndBody() throws Exception {
        mvc.perform(get(URL).param("employeeId", "84").header("X-Employee-Id", "84")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"employeeId\":84}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].leaveTypeId").value(1))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_RULES", "INACTIVE", "UNREQUESTABLE"})
    void validPolicyWithoutRequestableTypesReturns200EmptyArray(String reason) throws Exception {
        if (reason.equals("NO_RULES")) {
            jdbcTemplate.update("delete from leave_policy_type_rule where policy_version_id=11");
        } else if (reason.equals("INACTIVE")) {
            jdbcTemplate.update("update leave_type set is_active=false");
        } else {
            jdbcTemplate.update("update leave_policy_type_rule set is_requestable=false where policy_version_id=11");
        }
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(content().json(
                "{\"success\":true,\"error\":null,\"data\":[]}", JsonCompareMode.STRICT));
    }

    static Stream<Arguments> units() {
        List<Arguments> cases = new ArrayList<>();
        for (String master : List.of("DAY", "HALF_DAY", "HOUR", "MIXED")) {
            for (String policy : List.of("FULL", "HALF", "HOUR", "MIXED")) {
                boolean compatible = master.equals("MIXED") || (master.equals("DAY") && policy.equals("FULL"))
                        || (master.equals("HALF_DAY") && policy.equals("HALF")) || (master.equals("HOUR") && policy.equals("HOUR"));
                cases.add(Arguments.of(master, policy, compatible));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("units")
    void validatesAndSerializesAllSixteenUnitCombinations(String master, String policy, boolean compatible) throws Exception {
        jdbcTemplate.update("update leave_type set request_unit=? where leave_type_id=1", master);
        jdbcTemplate.update("update leave_policy_type_rule set allowed_units=? where rule_id=1", policy);
        if (compatible) {
            mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].allowedUnits").value(policy));
        } else {
            assertSettingsConflict();
        }
    }

    static Stream<Arguments> limits() {
        return Stream.of(Arguments.of(480, 60, 240, 240, true), Arguments.of(480, null, null, 480, true),
                Arguments.of(null, 30, 240, 240, true), Arguments.of(null, null, null, null, true),
                Arguments.of(480, 480, 480, 480, true), Arguments.of(480, 480, null, 480, true),
                Arguments.of(120, 121, null, null, false), Arguments.of(120, null, 121, null, false));
    }

    @ParameterizedTest
    @MethodSource("limits")
    void computesEffectiveLimitsAndRejectsExplicitMasterCapViolations(
            Integer master, Integer min, Integer max, Integer effectiveMax, boolean valid
    ) throws Exception {
        jdbcTemplate.update("update leave_type set max_minutes=? where leave_type_id=1", master);
        jdbcTemplate.update("update leave_policy_type_rule set min_minutes=?,max_minutes=? where rule_id=1", min, max);
        if (!valid) {
            assertSettingsConflict();
            return;
        }
        MvcResult result = mvc.perform(get(URL)).andExpect(status().isOk()).andReturn();
        JsonNode first = responseData(result).get(0);
        assertThat(first.get("effectiveMinMinutes").isNull()).isEqualTo(min == null);
        assertThat(first.get("effectiveMaxMinutes").isNull()).isEqualTo(effectiveMax == null);
        if (min != null) {
            assertThat(first.get("effectiveMinMinutes").asInt()).isEqualTo(min);
        }
        if (effectiveMax != null) {
            assertThat(first.get("effectiveMaxMinutes").asInt()).isEqualTo(effectiveMax);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "PAST", "FUTURE", "OVERLAP", "NULL_POINTER"})
    void reusesCurrentPolicyErrorsWithoutQueryingTypes(String state) throws Exception {
        boolean notFound = List.of("NONE", "PAST", "FUTURE").contains(state);
        if (state.equals("NONE")) {
            jdbcTemplate.update("delete from employee_leave_policy_assignment where emp_id=42");
        } else if (state.equals("PAST")) {
            jdbcTemplate.update("update employee_leave_policy_assignment set effective_to='2001-01-01T00:00:00Z' where emp_id=42");
        } else if (state.equals("FUTURE")) {
            jdbcTemplate.update("update employee_leave_policy_assignment set effective_from='3000-01-01T00:00:00Z' where emp_id=42");
        } else if (state.equals("OVERLAP")) {
            jdbcTemplate.update("""
                    insert into employee_leave_policy_assignment (assignment_id,emp_id,policy_id,effective_from)
                    values (32,42,2,'2001-01-01T00:00:00Z')
                    """);
        } else {
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null where policy_id=1");
        }
        Map<String, List<Map<String, Object>>> before = storedRows();
        mvc.perform(get(URL)).andExpect(status().is(notFound ? 404 : 409))
                .andExpect(jsonPath("$.error.code").value(notFound ? "LEAVE_010" : "LEAVE_011"));
        assertThat(SqlCapture.statements.get()).hasSize(1);
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void foreignActiveReferenceUsesExisting409AndNeverReadsItsRules() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("alter table leave_policy_group drop constraint fk_policy_group_active_version");
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id=21 where policy_id=1");
            try {
                mvc.perform(get(URL)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_011"));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                status.setRollbackOnly();
            }
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from pg_constraint where conname='fk_policy_group_active_version'", Integer.class)).isEqualTo(1);
    }

    @Test
    void actualActivationAndAssignmentSwitchSelectNewVersionRules() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        activateVersion(12);
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].leaveTypeId").value(2)).andExpect(jsonPath("$.data[1].leaveTypeId").value(6));
        assignOtherGroup();
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].leaveTypeId").value(7));
    }

    @Test
    void masterDeactivationThroughExistingPatchImmediatelyExcludesTypeWithoutDeletingItsRule() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        List<Map<String, Object>> rulesBefore = jdbcTemplate.queryForList("select *,xmin::text from leave_policy_type_rule order by rule_id");
        deactivateMaster();
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].leaveTypeId").value(2));
        assertThat(jdbcTemplate.queryForList("select *,xmin::text from leave_policy_type_rule order by rule_id")).isEqualTo(rulesBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVATION", "ASSIGNMENT", "MASTER_DISABLE", "RULE_CHANGE"})
    void commitBetweenPolicyAndTypeSelectsCannotMixSnapshots(String action) throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SqlCapture.beforeTypesQuery.set(() -> {
                assertThat(jdbcTemplate.queryForObject("show transaction_isolation", String.class)).isEqualTo("repeatable read");
                try {
                    executor.submit(() -> performChange(action)).get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3))
                    .andExpect(jsonPath("$.data[0].leaveTypeId").value(1))
                    .andExpect(jsonPath("$.data[0].effectiveMinMinutes").value(60));
        } finally {
            SqlCapture.beforeTypesQuery.remove();
        }
        if (action.equals("MASTER_DISABLE")) {
            mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].leaveTypeId").value(2));
        } else if (action.equals("ASSIGNMENT")) {
            mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].leaveTypeId").value(7));
        } else {
            mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].leaveTypeId").value(2));
            if (action.equals("RULE_CHANGE")) {
                activateVersion(11);
                mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].effectiveMinMinutes").value(120));
            }
        }
    }

    @Test
    void unauthenticatedIdentityStopsBeforeBothReads() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        mvc.perform(get(URL)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    private void performChange(String action) {
        try {
            if (action.equals("MASTER_DISABLE")) {
                deactivateMaster();
            } else if (action.equals("ASSIGNMENT")) {
                assignOtherGroup();
            } else {
                activateVersion(12);
                if (action.equals("RULE_CHANGE")) {
                    mvc.perform(put("/api/v1/leaves/admin/policies/1/versions/11/type-rules/1").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isRequestable\":true,\"allowedUnits\":\"MIXED\",\"minMinutes\":120,\"maxMinutes\":240}"))
                            .andExpect(status().isOk());
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void activateVersion(long version) throws Exception {
        mvc.perform(put("/api/v1/leaves/admin/policies/1/active-version").contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionId\":" + version + "}")).andExpect(status().isOk());
    }

    private void deactivateMaster() throws Exception {
        mvc.perform(patch("/api/v1/leaves/admin/types/1").contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
                .andExpect(status().isOk());
    }

    private void assignOtherGroup() throws Exception {
        mvc.perform(post("/api/v1/leaves/admin/employees/42/policy-assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyId\":2}")).andExpect(status().isCreated());
    }

    private void assertSettingsConflict() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        mvc.perform(get(URL)).andExpect(status().isConflict()).andExpect(content().json("""
                {"success":false,"error":{"code":"LEAVE_012","message":"현재 휴가 정책의 신청 규칙과 휴가 유형 설정이 일치하지 않습니다."},"data":null}
                """, JsonCompareMode.STRICT));
        assertThat(storedRows()).isEqualTo(before);
    }

    private JsonNode responseData(MvcResult result) {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
    }

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("employee_leave_policy_assignment", "leave_policy_group", "leave_policy_version",
                "leave_policy_type_rule", "leave_accrual_rule", "leave_type", "leave_request", "leave_grant", "leave_ledger", "leave_account")) {
            rows.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }
}
