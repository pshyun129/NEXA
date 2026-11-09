package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.policy.dto.EmployeeLeavePolicyAssignmentCreateRequest;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyActivationRequest;
import com.ssafy.leaveservice.domain.policy.dto.MyLeavePolicyResponse;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.repository.EmployeeLeavePolicyAssignmentRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.service.EmployeeLeavePolicyAssignmentService;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyActivationService;
import com.ssafy.leaveservice.domain.policy.service.MyLeavePolicyService;
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

import java.time.OffsetDateTime;
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
        "leave.test.database-owner=MyLeavePolicyPostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.policy.MyLeavePolicyPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class MyLeavePolicyPostgresIntegrationTest {

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<List<Boolean>> readOnlyFlags = ThreadLocal.withInitial(ArrayList::new);

        public SqlCapture() {
        }

        @Override
        public String inspect(String sql) {
            statements.get().add(sql.toLowerCase());
            readOnlyFlags.get().add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return sql;
        }
    }

    private static final String URL = "/api/v1/leaves/me/policy";
    private static final OffsetDateTime BOUNDARY = OffsetDateTime.parse("2026-11-01T00:00:00Z");

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmployeeLeavePolicyAssignmentRepository assignmentRepository;
    @Autowired private LeavePolicyGroupRepository groupRepository;
    @Autowired private LeavePolicyActivationService activationService;
    @Autowired private EmployeeLeavePolicyAssignmentService assignmentService;
    @Autowired private MyLeavePolicyService service;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        SqlCapture.statements.get().clear();
        SqlCapture.readOnlyFlags.get().clear();
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : List.of("leave_ledger", "leave_request", "leave_grant", "leave_accrual_rule", "leave_policy_type_rule",
                "employee_leave_policy_assignment", "leave_account", "leave_type", "leave_balance_type",
                "leave_policy_version", "leave_policy_group")) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        jdbcTemplate.update("insert into leave_policy_group (policy_id,name) values (1,'일반 정책'),(2,'다른 직원 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id,policy_id,version_no,base_type,fiscal_start,
                    unit_minutes,carry_over_allowed,carry_over_limit_minutes,expiry_rule,effective_from,effective_to)
                values (11,1,1,'HIRE_DATE',null,30,false,null,'END_OF_VALIDITY','2099-01-01T00:00:00Z','2100-01-01T00:00:00Z'),
                       (12,1,2,'FISCAL_YEAR','01-01',60,true,240,'CUSTOM','2000-01-01T00:00:00Z','2001-01-01T00:00:00Z'),
                       (13,1,3,'HIRE_DATE',null,17,false,null,'END_OF_VALIDITY',null,null),
                       (21,2,1,'HIRE_DATE',null,90,false,null,'END_OF_VALIDITY',null,null)
                """);
        jdbcTemplate.update("insert into leave_type (leave_type_id,name,balance_mode,request_unit) values (1,'연차','NONE','DAY')");
        jdbcTemplate.update("""
                insert into leave_policy_type_rule (rule_id,policy_version_id,leave_type_id)
                values (1,11,1),(2,12,1),(3,13,1),(4,21,1)
                """);
        jdbcTemplate.update("insert into leave_accrual_rule (rule_id,policy_version_id,frequency,grant_minutes) values (1,11,'MONTHLY',480)");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=21 where policy_id=2");
        insertAssignment(31, 42, 1, "2000-01-01T00:00:00Z", null);
        insertAssignment(81, 84, 2, "2000-01-01T00:00:00Z", null);
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id,name) values (1,'연차')");
        jdbcTemplate.update("insert into leave_account (account_id,emp_id,balance_type_id) values (1,42,1)");
        jdbcTemplate.update("insert into leave_request (leave_req_id,policy_version_id,leave_type_id,emp_id,status) values (1,11,1,42,'CANCELLED')");
        jdbcTemplate.update("""
                insert into leave_grant (grant_id,account_id,policy_version_id,rule_id,grant_kind,original_minutes,valid_from)
                values (1,1,11,1,'AUTO',480,'2020-01-01T00:00:00Z')
                """);
        jdbcTemplate.update("""
                insert into leave_ledger (entry_id,account_id,grant_id,leave_req_id,event_type,quantity_delta,idempotency_key)
                values (1,1,1,1,'GRANT',480,'my-policy-history')
                """);
    }

    @Test
    void nonAdminEmployeeGetsExactlyOwnAssignmentAndActiveSettingsWithoutChangingAnyRows() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        MvcResult result = mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.assignmentId").value(31))
                .andExpect(jsonPath("$.data.policyId").value(1))
                .andExpect(jsonPath("$.data.policyName").value("일반 정책"))
                .andExpect(jsonPath("$.data.assignmentEffectiveTo").isEmpty())
                .andExpect(jsonPath("$.data.policyVersionId").value(11))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.baseType").value("HIRE_DATE"))
                .andExpect(jsonPath("$.data.fiscalStart").isEmpty())
                .andExpect(jsonPath("$.data.unitMinutes").value(30))
                .andExpect(jsonPath("$.data.carryOverAllowed").value(false))
                .andExpect(jsonPath("$.data.carryOverLimitMinutes").isEmpty())
                .andExpect(jsonPath("$.data.expiryRule").value("END_OF_VALIDITY")).andReturn();
        JsonNode policy = responseData(result);
        assertThat(policy.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "assignmentId", "policyId", "policyName", "assignmentEffectiveFrom", "assignmentEffectiveTo",
                "policyVersionId", "versionNo", "baseType", "fiscalStart", "unitMinutes", "carryOverAllowed",
                "carryOverLimitMinutes", "expiryRule", "effectiveFrom", "effectiveTo"
        );
        assertThat(OffsetDateTime.parse(policy.get("effectiveFrom").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2099-01-01T00:00:00Z").toInstant());
        assertThat(storedRows()).isEqualTo(before);
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void singleScalarJoinQueryUsesReadOnlyTransactionAndNoWriteOrAdvisoryLocks() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk());
        assertThat(SqlCapture.statements.get()).hasSize(1);
        assertThat(SqlCapture.statements.get().getFirst()).startsWith("select ")
                .contains("employee_leave_policy_assignment", "left join leave_policy_group", "left join leave_policy_version")
                .doesNotContain("for update", "for share", "for no key update", "pg_advisory", "leave_accrual_rule", "leave_policy_type_rule");
        assertThat(SqlCapture.readOnlyFlags.get()).containsExactly(true);
    }

    @Test
    void queryBodyAndHeaderCannotSelectAnotherEmployeesPolicy() throws Exception {
        mvc.perform(get(URL).param("employeeId", "84").header("X-Employee-Id", "84")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"employeeId\":84}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignmentId").value(31))
                .andExpect(jsonPath("$.data.policyVersionId").value(11));
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "PAST", "FUTURE"})
    void absenceOfCurrentAssignmentReturns404EvenIfOtherEmployeesHavePolicies(String state) throws Exception {
        jdbcTemplate.update("delete from employee_leave_policy_assignment where emp_id=42");
        if (state.equals("PAST")) {
            insertAssignment(32, 42, 1, "1900-01-01T00:00:00Z", "2000-01-01T00:00:00Z");
        } else if (state.equals("FUTURE")) {
            insertAssignment(32, 42, 1, "3000-01-01T00:00:00Z", null);
        }
        mvc.perform(get(URL)).andExpect(status().isNotFound()).andExpect(content().json("""
                {"success":false,"error":{"code":"LEAVE_010","message":"현재 적용되는 휴가 정책 배정이 없습니다."},"data":null}
                """, JsonCompareMode.STRICT));
    }

    @Test
    void selectsOnlyCurrentAssignmentAmongPastCurrentAndFuture() throws Exception {
        insertAssignment(32, 42, 2, "1900-01-01T00:00:00Z", "2000-01-01T00:00:00Z");
        insertAssignment(33, 42, 2, "3000-01-01T00:00:00Z", null);
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.assignmentId").value(31));
    }

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("2026-11-01T00:00:00Z", null, 1),
                Arguments.of("2000-01-01T00:00:00Z", "2026-11-01T00:00:00Z", 0),
                Arguments.of("2000-01-01T00:00:00Z", "2026-11-01T00:00:00.000001Z", 1),
                Arguments.of("2026-11-01T00:00:00.000001Z", null, 0),
                Arguments.of("2026-11-01T09:00:00+09:00", null, 1),
                Arguments.of("2000-01-01T00:00:00Z", "2026-11-01T09:00:00+09:00", 0)
        );
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    void repositoryAppliesInclusiveStartAndExclusiveEndAtExactInstant(String from, String to, int expectedCount) {
        jdbcTemplate.update("delete from employee_leave_policy_assignment where emp_id=42");
        insertAssignment(31, 42, 1, from, to);
        assertThat(assignmentRepository.findApplicablePolicies(42L, BOUNDARY)).hasSize(expectedCount);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsOverlappingAssignmentsEvenWhenOneGroupHasNoActiveVersion(boolean missingActiveVersion) throws Exception {
        insertAssignment(32, 42, 2, "2001-01-01T00:00:00Z", null);
        if (missingActiveVersion) {
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null where policy_id=2");
        }
        assertConflict();
    }

    @Test
    void rejectsNullPointerWithoutFallingBackToHighestOrPeriodMatchingVersion() throws Exception {
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null where policy_id=1");
        assertConflict();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOREIGN_VERSION", "MISSING_VERSION", "MISSING_GROUP"})
    void leftJoinsPreserveBrokenReferenceAs409InIsolatedRollbackFixture(String invalidReference) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if (invalidReference.equals("MISSING_GROUP")) {
                jdbcTemplate.execute("alter table employee_leave_policy_assignment drop constraint fk_assignment_policy");
                jdbcTemplate.update("update employee_leave_policy_assignment set policy_id=999 where emp_id=42");
            } else {
                jdbcTemplate.execute("alter table leave_policy_group drop constraint fk_policy_group_active_version");
                jdbcTemplate.update("update leave_policy_group set active_policy_version_id=? where policy_id=1",
                        invalidReference.equals("FOREIGN_VERSION") ? 21 : 999);
            }
            try {
                assertConflict();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                status.setRollbackOnly();
            }
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from pg_constraint where conname in ('fk_assignment_policy','fk_policy_group_active_version')", Integer.class)).isEqualTo(2);
    }

    @Test
    void actualActivationAndReactivationChangeSettingsWithoutUsingVersionPeriodsOrNumbers() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        for (long versionId : List.of(12L, 11L)) {
            mvc.perform(put("/api/v1/leaves/admin/policies/1/active-version").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"versionId\":" + versionId + "}"))
                    .andExpect(status().isOk());
            MvcResult result = mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.policyVersionId").value(versionId))
                    .andExpect(jsonPath("$.data.unitMinutes").value(versionId == 12 ? 60 : 30))
                    .andExpect(jsonPath("$.data.carryOverAllowed").value(versionId == 12)).andReturn();
            if (versionId == 12) {
                JsonNode policy = responseData(result);
                assertThat(policy.get("baseType").asString()).isEqualTo("FISCAL_YEAR");
                assertThat(policy.get("fiscalStart").asString()).isEqualTo("01-01");
                assertThat(policy.get("carryOverLimitMinutes").asInt()).isEqualTo(240);
                assertThat(policy.get("expiryRule").asString()).isEqualTo("CUSTOM");
            }
        }
    }

    @Test
    void inactiveMasterDoesNotTurnBasicPolicyReadIntoRequestEligibilityCalculation() throws Exception {
        jdbcTemplate.update("update leave_type set is_active=false where leave_type_id=1");
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.policyVersionId").value(11));
    }

    @Test
    void returnsNullableVersionPeriodsAndCurrentPolicyGroupName() throws Exception {
        jdbcTemplate.update("update leave_policy_version set effective_from=null,effective_to=null where policy_version_id=11");
        jdbcTemplate.update("update leave_policy_group set name='바뀐 현재 이름' where policy_id=1");
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.policyName").value("바뀐 현재 이름"))
                .andExpect(jsonPath("$.data.effectiveFrom").isEmpty()).andExpect(jsonPath("$.data.effectiveTo").isEmpty());
    }

    @Test
    void serializesBothAssignmentAndVersionPeriodsAsOffsetDateTimeInstants() throws Exception {
        jdbcTemplate.update("""
                update employee_leave_policy_assignment set effective_from='2000-01-01T09:00:00+09:00',
                effective_to='3000-01-01T04:00:00-05:00' where assignment_id=31
                """);
        MvcResult result = mvc.perform(get(URL)).andExpect(status().isOk()).andReturn();
        JsonNode policy = responseData(result);
        assertThat(OffsetDateTime.parse(policy.get("assignmentEffectiveFrom").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2000-01-01T00:00:00Z").toInstant());
        assertThat(OffsetDateTime.parse(policy.get("assignmentEffectiveTo").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("3000-01-01T09:00:00Z").toInstant());
        assertThat(OffsetDateTime.parse(policy.get("effectiveTo").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2100-01-01T00:00:00Z").toInstant());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uncommittedActivationDoesNotBlockReadOrMixVersionSettings(boolean rollback) throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                activationService.activateVersion(1L, new LeavePolicyActivationRequest(12L));
                try {
                    MvcResult concurrentRead = executor.submit(() -> mvc.perform(get(URL)).andReturn()).get(10, TimeUnit.SECONDS);
                    assertThat(concurrentRead.getResponse().getStatus()).isEqualTo(200);
                    JsonNode policy = responseData(concurrentRead);
                    assertThat(policy.get("policyVersionId").asLong()).isEqualTo(11);
                    assertThat(policy.get("versionNo").asInt()).isEqualTo(1);
                    assertThat(policy.get("unitMinutes").asInt()).isEqualTo(30);
                    assertThat(policy.get("carryOverAllowed").asBoolean()).isFalse();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                if (rollback) {
                    status.setRollbackOnly();
                }
            });
        }
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyVersionId").value(rollback ? 11 : 12))
                .andExpect(jsonPath("$.data.unitMinutes").value(rollback ? 30 : 60));
    }

    @Test
    void scalarProjectionDoesNotReuseStaleManagedGroupPointer() {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                LeavePolicyGroup staleGroup = groupRepository.findById(1L).orElseThrow();
                try {
                    executor.submit(() -> activationService.activateVersion(1L, new LeavePolicyActivationRequest(12L)))
                            .get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                assertThat(staleGroup.getActivePolicyVersionId()).isEqualTo(11);
                MyLeavePolicyResponse policy = service.getMyPolicy();
                assertThat(policy.policyVersionId()).isEqualTo(12);
                assertThat(policy.unitMinutes()).isEqualTo(60);
            });
        }
    }

    @Test
    void uncommittedAssignmentSwitchReturnsCompleteOldThenNewPolicyWithoutBlocking() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assignmentService.createPolicyAssignment(42L, new EmployeeLeavePolicyAssignmentCreateRequest(2L, null, null));
                try {
                    MvcResult concurrentRead = executor.submit(() -> mvc.perform(get(URL)).andReturn()).get(10, TimeUnit.SECONDS);
                    assertThat(concurrentRead.getResponse().getStatus()).isEqualTo(200);
                    assertThat(responseData(concurrentRead).get("assignmentId").asLong()).isEqualTo(31);
                    assertThat(responseData(concurrentRead).get("policyVersionId").asLong()).isEqualTo(11);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.policyId").value(2))
                .andExpect(jsonPath("$.data.policyVersionId").value(21)).andExpect(jsonPath("$.data.unitMinutes").value(90));
    }

    @Test
    void unauthenticatedRequestUsesExisting401AndDoesNotReadDatabase() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        mvc.perform(get(URL)).andExpect(status().isUnauthorized()).andExpect(content().json("""
                {"success":false,"error":{"code":"AUTH_001","message":"인증이 필요합니다."},"data":null}
                """, JsonCompareMode.STRICT));
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    private void assertConflict() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        mvc.perform(get(URL)).andExpect(status().isConflict()).andExpect(content().json("""
                {"success":false,"error":{"code":"LEAVE_011","message":"현재 적용 휴가 정책의 배정 또는 활성 버전 상태가 올바르지 않습니다."},"data":null}
                """, JsonCompareMode.STRICT));
        assertThat(storedRows()).isEqualTo(before);
    }

    private JsonNode responseData(MvcResult result) {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
    }

    private void insertAssignment(long id, long employeeId, long policyId, String from, String to) {
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (assignment_id,emp_id,policy_id,effective_from,effective_to)
                values (?,?,?,cast(? as timestamptz),cast(? as timestamptz))
                """, id, employeeId, policyId, from, to);
    }

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("employee_leave_policy_assignment", "leave_policy_group", "leave_policy_version",
                "leave_accrual_rule", "leave_policy_type_rule", "leave_type", "leave_request", "leave_grant", "leave_ledger")) {
            rows.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }
}
