package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyActivationRequest;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyGroupUpdateRequest;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyActivationService;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyGroupService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=LeavePolicyActivationPostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.policy.LeavePolicyActivationPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeavePolicyActivationPostgresIntegrationTest {

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);

        public SqlCapture() {
        }

        @Override
        public String inspect(String sql) {
            statements.get().add(sql.toLowerCase());
            return sql;
        }
    }

    private static final String URL = "/api/v1/leaves/admin/policies/1/active-version";
    private static final String VERSION_URL = "/api/v1/leaves/admin/policies/1/versions/15";

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LeavePolicyActivationService service;
    @Autowired private LeavePolicyGroupService groupService;
    @Autowired private LeavePolicyGroupRepository groupRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @MockitoBean private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SqlCapture.statements.get().clear();
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : List.of("leave_ledger", "leave_request", "leave_grant", "leave_accrual_rule", "leave_policy_type_rule",
                "employee_leave_policy_assignment", "leave_account", "leave_type", "leave_balance_type",
                "leave_policy_version", "leave_policy_group")) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        jdbcTemplate.update("insert into leave_policy_group (policy_id,name) values (1,'일반 정책'),(2,'다른 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id,policy_id,version_no,base_type)
                values (11,1,1,'HIRE_DATE'),(15,1,2,'HIRE_DATE'),(16,1,3,'FISCAL_YEAR'),(21,2,1,'HIRE_DATE')
                """);
        jdbcTemplate.update("""
                insert into leave_type (leave_type_id,name,balance_mode,request_unit,max_minutes,is_active)
                values (1,'일 단위','NONE','DAY',120,true),(2,'혼합','NONE','MIXED',120,true),
                       (3,'비활성','NONE','DAY',10,false)
                """);
        jdbcTemplate.update("""
                insert into leave_policy_type_rule
                    (rule_id,policy_version_id,leave_type_id,is_requestable,allowed_units,min_minutes,max_minutes)
                values (1,11,1,true,'FULL',null,null),(2,15,1,true,'FULL',30,90),
                       (3,15,2,true,'HOUR',null,120),(4,16,1,true,'FULL',null,null)
                """);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=21 where policy_id=2");
    }

    @Test
    void setsFirstPointerAndReflectsItInVersionListAndDetailOnly() throws Exception {
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null where policy_id=1");
        activate(15).andExpect(status().isOk()).andExpect(content().json("""
                {"success":true,"error":null,"data":{"policyId":1,"activePolicyVersionId":15}}
                """, JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/leaves/admin/policies/1/versions"))
                .andExpect(jsonPath("$.data[0].isActive").value(false))
                .andExpect(jsonPath("$.data[1].isActive").value(true))
                .andExpect(jsonPath("$.data[2].isActive").value(false));
        mvc.perform(get(VERSION_URL)).andExpect(jsonPath("$.data.isActive").value(true));
        mvc.perform(get("/api/v1/leaves/admin/policies/1")).andExpect(content().json("""
                {"success":true,"error":null,"data":{"policyId":1,"name":"일반 정책"}}
                """, JsonCompareMode.STRICT));
        assertThat(pointer(2)).isEqualTo(21);
    }

    @Test
    void switchesAndReactivatesHistoricalVersionWithoutChangingAnyBusinessData() throws Exception {
        addHistory();
        Map<String, List<Map<String, Object>>> before = businessRows();
        activate(15).andExpect(status().isOk());
        activate(11).andExpect(status().isOk());
        activate(15).andExpect(status().isOk());
        assertThat(pointer(1)).isEqualTo(15);
        assertThat(businessRows()).isEqualTo(before);
        assertThat(pointer(2)).isEqualTo(21);
    }

    @Test
    void repeatedSelectionSkipsCompletenessChecksAndPhysicalUpdate() throws Exception {
        jdbcTemplate.update("delete from leave_policy_type_rule where policy_version_id=11");
        String xmin = groupXmin();
        Map<String, List<Map<String, Object>>> before = businessRows();
        activate(11).andExpect(status().isOk()).andExpect(jsonPath("$.data.activePolicyVersionId").value(11));
        assertThat(groupXmin()).isEqualTo(xmin);
        assertThat(businessRows()).isEqualTo(before);
    }

    @Test
    void effectiveDatesAndHighestVersionDoNotChooseActiveState() throws Exception {
        jdbcTemplate.update("update leave_policy_version set effective_from='2099-01-01T00:00:00Z',effective_to='2100-01-01T00:00:00Z' where policy_version_id=15");
        activate(15).andExpect(status().isOk());
        assertThat(pointer(1)).isEqualTo(15);
    }

    @Test
    void keepsManagedGroupSynchronizedAfterExplicitUpdate() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            LeavePolicyGroup group = groupRepository.findById(1L).orElseThrow();
            service.activateVersion(1L, new LeavePolicyActivationRequest(15L));
            assertThat(group.getActivePolicyVersionId()).isEqualTo(15);
            assertThat(entityManager.contains(group)).isTrue();
            group.updateName("동일 트랜잭션 이름");
        });
        assertThat(pointer(1)).isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject("select name from leave_policy_group where policy_id=1", String.class))
                .isEqualTo("동일 트랜잭션 이름");
    }

    @Test
    void actualHibernateUpdatesSeparateNameFromActivePointer() throws Exception {
        activate(15).andExpect(status().isOk());
        List<String> pointerUpdates = SqlCapture.statements.get().stream()
                .filter(sql -> sql.startsWith("update leave_policy_group"))
                .toList();
        assertThat(pointerUpdates).hasSize(1);
        assertThat(pointerUpdates.getFirst()).contains("set active_policy_version_id = ?", "where policy_id = ?")
                .doesNotContain("name=");

        SqlCapture.statements.get().clear();
        mvc.perform(patch("/api/v1/leaves/admin/policies/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"SQL 검증\"}")).andExpect(status().isOk());
        List<String> nameUpdates = SqlCapture.statements.get().stream()
                .filter(sql -> sql.startsWith("update leave_policy_group"))
                .toList();
        assertThat(nameUpdates).hasSize(1);
        assertThat(nameUpdates.getFirst()).contains("set name=?").doesNotContain("active_policy_version_id");
        assertThat(pointer(1)).isEqualTo(15);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"versionId\":null}", "{\"versionId\":0}", "{\"versionId\":-1}",
            "{\"versionId\":\"15\"}", "{\"versionId\":15.0}", "{\"versionId\":true}", "{\"versionId\":[]}",
            "{\"versionId\":{}}", "{\"versionId\":9223372036854775808}", "[]", "null", "15"})
    void rejectsInvalidIdsWithoutMutation(String body) throws Exception {
        String xmin = groupXmin();
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(groupXmin()).isEqualTo(xmin);
    }

    @ParameterizedTest
    @ValueSource(longs = {999, 21})
    void rejectsMissingOrForeignVersions(long versionId) throws Exception {
        activate(versionId).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_005"));
        assertThat(pointer(1)).isEqualTo(11);
    }

    @Test
    void rejectsMissingGroupAndDeniedAdmin() throws Exception {
        mvc.perform(put(URL.replace("/1/", "/999/")).contentType(MediaType.APPLICATION_JSON).content("{\"versionId\":15}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_004"));
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        activate(15).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        activate(11).andExpect(status().isForbidden());
        assertThat(pointer(1)).isEqualTo(11);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_RULES", "NOT_REQUESTABLE", "INACTIVE_MASTERS"})
    void rejectsIncompleteVersions(String state) throws Exception {
        if (state.equals("NO_RULES")) {
            jdbcTemplate.update("delete from leave_policy_type_rule where policy_version_id=15");
        } else if (state.equals("NOT_REQUESTABLE")) {
            jdbcTemplate.update("update leave_policy_type_rule set is_requestable=false where policy_version_id=15");
        } else {
            jdbcTemplate.update("update leave_type set is_active=false");
        }
        assertActivationConflictPreservesRows();
    }

    static Stream<Arguments> unitCompatibility() {
        List<Arguments> cases = new ArrayList<>();
        for (String masterUnit : List.of("DAY", "HALF_DAY", "HOUR", "MIXED")) {
            for (String policyUnit : List.of("FULL", "HALF", "HOUR", "MIXED")) {
                boolean compatible = masterUnit.equals("MIXED")
                        || (masterUnit.equals("DAY") && policyUnit.equals("FULL"))
                        || (masterUnit.equals("HALF_DAY") && policyUnit.equals("HALF"))
                        || (masterUnit.equals("HOUR") && policyUnit.equals("HOUR"));
                cases.add(Arguments.of(masterUnit, policyUnit, compatible));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("unitCompatibility")
    void checksEveryRequestableRuleAgainstMasterEvenWhenAnotherRuleIsValid(
            String masterUnit, String policyUnit, boolean compatible
    ) throws Exception {
        jdbcTemplate.update("update leave_type set request_unit=? where leave_type_id=1", masterUnit);
        jdbcTemplate.update("update leave_policy_type_rule set allowed_units=? where rule_id=2", policyUnit);
        if (compatible) {
            activate(15).andExpect(status().isOk());
        } else {
            assertActivationConflictPreservesRows();
        }
    }

    static Stream<Arguments> timeLimits() {
        return Stream.of(Arguments.of(121, null, 120, false), Arguments.of(null, 121, 120, false),
                Arguments.of(120, 120, 120, true), Arguments.of(120, null, 120, true),
                Arguments.of(null, null, 120, true), Arguments.of(999, 1000, null, true));
    }

    @ParameterizedTest
    @MethodSource("timeLimits")
    void validatesExplicitTimeLimits(Integer min, Integer max, Integer masterMax, boolean valid) throws Exception {
        jdbcTemplate.update("update leave_type set max_minutes=? where leave_type_id=1", masterMax);
        jdbcTemplate.update("update leave_policy_type_rule set min_minutes=?,max_minutes=? where rule_id=2", min, max);
        if (valid) {
            activate(15).andExpect(status().isOk());
        } else {
            assertActivationConflictPreservesRows();
        }
    }

    @Test
    void ignoresIncompatibleInactiveOrUnrequestableTypesAndAllowsOnlyInactiveAccrualRules() throws Exception {
        jdbcTemplate.update("""
                insert into leave_policy_type_rule (rule_id,policy_version_id,leave_type_id,allowed_units,max_minutes)
                values (9,15,3,'MIXED',1000)
                """);
        jdbcTemplate.update("update leave_policy_type_rule set is_requestable=false,allowed_units='MIXED' where rule_id=2");
        jdbcTemplate.update("""
                insert into leave_accrual_rule (rule_id,policy_version_id,frequency,grant_minutes,is_active)
                values (7,15,'CUSTOM',15,false)
                """);
        Map<String, List<Map<String, Object>>> before = businessRows();
        activate(15).andExpect(status().isOk());
        assertThat(businessRows()).isEqualTo(before);
    }

    @Test
    void rejectsHistoricalVersionThatNowConflictsWithMaster() throws Exception {
        addHistory();
        jdbcTemplate.update("update leave_type set max_minutes=60 where leave_type_id=1");
        assertActivationConflictPreservesRows();
    }

    @Test
    void rollsBackWhenPointerUpdateViolatesDatabaseConstraint() throws Exception {
        jdbcTemplate.execute("alter table leave_policy_group add constraint test_activation_failure check (active_policy_version_id <> 15)");
        try {
            String xmin = groupXmin();
            Map<String, List<Map<String, Object>>> before = businessRows();
            activate(15).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
            assertThat(pointer(1)).isEqualTo(11);
            assertThat(groupXmin()).isEqualTo(xmin);
            assertThat(businessRows()).isEqualTo(before);
        } finally {
            jdbcTemplate.execute("alter table leave_policy_group drop constraint test_activation_failure");
        }
    }

    @Test
    void serializesDifferentActivationsAndLastAppliedRequestWins() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> second = transaction.execute(status -> {
                service.activateVersion(1L, new LeavePolicyActivationRequest(15L));
                Future<MvcResult> pending = executor.submit(() -> activate(16).andReturn());
                waitForLock("%leave_policy_group%");
                return pending;
            });
            assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(pointer(1)).isEqualTo(16);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE", "ACCRUAL_PUT", "TYPE_PUT"})
    void activationFirstMakesConcurrentBusinessMutationRejectActiveVersion(String action) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> second = transaction.execute(status -> {
                service.activateVersion(1L, new LeavePolicyActivationRequest(15L));
                Future<MvcResult> pending = executor.submit(() -> mvc.perform(mutation(action)).andReturn());
                waitForLock("%leave_policy_group%");
                return pending;
            });
            MvcResult result = second.get(15, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("error").get("code").asString())
                    .isEqualTo("LEAVE_006");
        }
        assertThat(pointer(1)).isEqualTo(15);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE", "ACCRUAL_PUT", "TYPE_PUT"})
    void mutationFirstMakesActivationObserveCommittedVersionAndRules(String action) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> second = transaction.execute(status -> {
                groupRepository.findByIdForUpdate(1L).orElseThrow();
                try {
                    assertThat(mvc.perform(mutation(action)).andReturn().getResponse().getStatus()).isEqualTo(200);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                Future<MvcResult> pending = executor.submit(() -> activate(15).andReturn());
                waitForLock("%leave_policy_group%");
                return pending;
            });
            assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(action.equals("DELETE") ? 404 : 200);
        }
        assertThat(pointer(1)).isEqualTo(action.equals("DELETE") ? 11 : 15);
    }

    @Test
    void masterPatchFirstMakesActivationWaitAndSeeNewInactiveState() throws Exception {
        jdbcTemplate.update("delete from leave_policy_type_rule where rule_id=3");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> second = transaction.execute(status -> {
                try {
                    assertThat(mvc.perform(patch("/api/v1/leaves/admin/types/1").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isActive\":false}")).andReturn().getResponse().getStatus()).isEqualTo(200);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                Future<MvcResult> pending = executor.submit(() -> activate(15).andReturn());
                waitForLock("%leave_type %");
                return pending;
            });
            assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(pointer(1)).isEqualTo(11);
    }

    @Test
    void activationHoldsMasterLocksUntilCommitButDoesNotPreventLaterDeactivation() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> second = transaction.execute(status -> {
                service.activateVersion(1L, new LeavePolicyActivationRequest(15L));
                Future<MvcResult> pending = executor.submit(() -> mvc.perform(patch("/api/v1/leaves/admin/types/1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}")).andReturn());
                waitForLock("%leave_type %");
                return pending;
            });
            assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(pointer(1)).isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject("select is_active from leave_type where leave_type_id=1", Boolean.class)).isFalse();
    }

    @Test
    void namePatchLoadedBeforeActivationCannotOverwriteNewPointer() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                groupService.updatePolicyGroup(1L, new LeavePolicyGroupUpdateRequest("변경한 이름"));
                try {
                    assertThat(executor.submit(() -> activate(15).andReturn()).get(15, TimeUnit.SECONDS)
                            .getResponse().getStatus()).isEqualTo(200);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                entityManager.flush();
            });
        }
        assertThat(pointer(1)).isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject("select name from leave_policy_group where policy_id=1", String.class)).isEqualTo("변경한 이름");
    }

    @Test
    void namePatchWaitingBehindActivationKeepsBothNewNameAndPointer() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> second = transaction.execute(status -> {
                service.activateVersion(1L, new LeavePolicyActivationRequest(15L));
                Future<MvcResult> pending = executor.submit(() -> mvc.perform(patch("/api/v1/leaves/admin/policies/1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"후속 이름\"}")).andReturn());
                waitForLock("%leave_policy_group%");
                return pending;
            });
            assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(pointer(1)).isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject("select name from leave_policy_group where policy_id=1", String.class)).isEqualTo("후속 이름");
    }

    private MockHttpServletRequestBuilder mutation(String action) {
        return switch (action) {
            case "PATCH" -> patch(VERSION_URL).contentType(MediaType.APPLICATION_JSON).content("{\"unitMinutes\":17}");
            case "DELETE" -> delete(VERSION_URL);
            case "ACCRUAL_PUT" -> put(VERSION_URL + "/accrual-rules").contentType(MediaType.APPLICATION_JSON).content("""
                    {"rules":[{"minServiceMonths":0,"frequency":"YEARLY","grantMinutes":480,"isActive":true}]}
                    """);
            case "TYPE_PUT" -> put(VERSION_URL + "/type-rules/1").contentType(MediaType.APPLICATION_JSON).content("""
                    {"isRequestable":true,"allowedUnits":"FULL","minMinutes":60,"maxMinutes":120}
                    """);
            default -> throw new IllegalArgumentException(action);
        };
    }

    private org.springframework.test.web.servlet.ResultActions activate(long versionId) throws Exception {
        return mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"versionId\":" + versionId + "}"));
    }

    private void assertActivationConflictPreservesRows() throws Exception {
        String xmin = groupXmin();
        Map<String, List<Map<String, Object>>> before = businessRows();
        activate(15).andExpect(status().isConflict()).andExpect(content().json("""
                {"success":false,"error":{"code":"LEAVE_008","message":"활성화할 수 없는 휴가 정책 설정입니다."},"data":null}
                """, JsonCompareMode.STRICT));
        assertThat(pointer(1)).isEqualTo(11);
        assertThat(groupXmin()).isEqualTo(xmin);
        assertThat(businessRows()).isEqualTo(before);
    }

    private Long pointer(long policyId) {
        return jdbcTemplate.queryForObject("select active_policy_version_id from leave_policy_group where policy_id=?", Long.class, policyId);
    }

    private String groupXmin() {
        return jdbcTemplate.queryForObject("select xmin::text from leave_policy_group where policy_id=1", String.class);
    }

    private Map<String, List<Map<String, Object>>> businessRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("leave_policy_version", "leave_type", "leave_policy_type_rule", "leave_accrual_rule",
                "leave_request", "leave_grant", "leave_ledger", "employee_leave_policy_assignment", "leave_account")) {
            rows.put(table, jdbcTemplate.queryForList("select *, xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }

    private void addHistory() {
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id,name) values (1,'연차')");
        jdbcTemplate.update("insert into leave_account (account_id,emp_id,balance_type_id) values (1,42,1)");
        jdbcTemplate.update("""
                insert into leave_accrual_rule (rule_id,policy_version_id,frequency,grant_minutes,is_active)
                values (5,15,'YEARLY',480,false)
                """);
        jdbcTemplate.update("insert into leave_request (leave_req_id,policy_version_id,leave_type_id,emp_id,status) values (1,15,1,42,'CANCELLED')");
        jdbcTemplate.update("""
                insert into leave_grant (grant_id,account_id,policy_version_id,rule_id,grant_kind,original_minutes,valid_from)
                values (1,1,15,5,'AUTO',480,'2020-01-01T00:00:00Z')
                """);
        jdbcTemplate.update("""
                insert into leave_ledger (account_id,grant_id,leave_req_id,event_type,quantity_delta,idempotency_key)
                values (1,1,1,'GRANT',480,'activation-history')
                """);
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (emp_id,policy_id,effective_from)
                values (42,1,'2020-01-01T00:00:00Z')
                """);
    }

    private void waitForLock(String queryPattern) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            jdbcTemplate.execute("select pg_stat_clear_snapshot()");
            Integer waiters = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()
                    and wait_event_type='Lock' and query like ?
                    """, Integer.class, queryPattern);
            if (waiters != null && waiters > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        fail("PostgreSQL 잠금 대기 확인 실패: " + queryPattern);
    }
}
