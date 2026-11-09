package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.policy.entity.EmployeeLeavePolicyAssignment;
import com.ssafy.leaveservice.domain.policy.repository.EmployeeLeavePolicyAssignmentRepository;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=EmployeeLeavePolicyAssignmentPostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.policy.EmployeeLeavePolicyAssignmentPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class EmployeeLeavePolicyAssignmentPostgresIntegrationTest {

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

    private static final String URL = "/api/v1/leaves/admin/employees/42/policy-assignments";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmployeeLeavePolicyAssignmentRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmployeeAccess employeeAccess;

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
        when(employeeAccess.currentEmployeeId()).thenReturn(7L);
        when(employeeAccess.isAdmin(7L)).thenReturn(true);
        jdbcTemplate.update("insert into leave_policy_group (policy_id,name) values (1,'일반 정책'),(2,'이전 정책'),(3,'버전 없는 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id,policy_id,version_no,base_type)
                values (11,1,1,'HIRE_DATE'),(12,1,2,'HIRE_DATE'),(21,2,1,'HIRE_DATE')
                """);
        jdbcTemplate.update("insert into leave_type (leave_type_id,name,balance_mode,request_unit) values (1,'연차','NONE','DAY')");
        jdbcTemplate.update("""
                insert into leave_policy_type_rule (rule_id,policy_version_id,leave_type_id)
                values (1,11,1),(2,12,1)
                """);
        jdbcTemplate.update("""
                insert into leave_accrual_rule (rule_id,policy_version_id,frequency,grant_minutes)
                values (1,11,'YEARLY',480),(2,12,'MONTHLY',60)
                """);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (assignment_id,emp_id,policy_id,effective_from,effective_to)
                values (30,42,1,'3000-01-01T09:00:00+09:00',null),
                       (10,42,2,'2000-01-01T09:00:00+09:00','2001-01-01T00:00:00Z'),
                       (20,42,1,'2001-01-01T00:00:00Z','3000-01-01T00:00:00Z'),
                       (40,84,2,'3001-01-01T00:00:00Z',null),
                       (50,43,3,'2020-01-01T00:00:00Z',null)
                """);
    }

    @Test
    void returnsPastCurrentAndFutureAssignmentsInDescendingStartOrderWithCurrentGroupNames() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        MvcResult result = mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].assignmentId").value(30))
                .andExpect(jsonPath("$.data[1].assignmentId").value(20))
                .andExpect(jsonPath("$.data[2].assignmentId").value(10))
                .andExpect(jsonPath("$.data[0].policyName").value("일반 정책"))
                .andExpect(jsonPath("$.data[2].policyName").value("이전 정책"))
                .andExpect(jsonPath("$.data[0].effectiveTo").isEmpty()).andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode assignment : response.get("data")) {
            assertThat(assignment.get("employeeId").asLong()).isEqualTo(42);
            assertThat(assignment.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                    "assignmentId", "employeeId", "policyId", "policyName", "effectiveFrom", "effectiveTo"
            );
        }
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void returnsSingleAssignmentEvenIfGroupHasNoVersions() throws Exception {
        MvcResult result = mvc.perform(get(URL.replace("/42/", "/43/"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assignmentId").value(50))
                .andExpect(jsonPath("$.data[0].employeeId").value(43))
                .andExpect(jsonPath("$.data[0].policyId").value(3))
                .andExpect(jsonPath("$.data[0].policyName").value("버전 없는 정책"))
                .andExpect(jsonPath("$.data[0].effectiveTo").isEmpty()).andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get(0).size()).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(longs = {999, Long.MAX_VALUE})
    void returnsEmptyListForEmployeeWithoutAssignmentsWithoutCallingHr(long employeeId) throws Exception {
        mvc.perform(get(URL.replace("/42/", "/" + employeeId + "/"))).andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}", JsonCompareMode.STRICT));
    }

    @Test
    void fetchesAllGroupsInOneReadOnlyQueryWithoutWriteLocksOrExtraQueries() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk());
        assertThat(SqlCapture.statements.get()).hasSize(1);
        assertThat(SqlCapture.statements.get().getFirst()).startsWith("select ")
                .contains("employee_leave_policy_assignment", "join leave_policy_group")
                .doesNotContain("for update", "for no key update", "leave_policy_version", "leave_accrual_rule", "leave_policy_type_rule");
        assertThat(SqlCapture.readOnlyFlags.get()).containsExactly(true);
    }

    @Test
    void preservesAssignmentsAndResponseAcrossActualActiveVersionChange() throws Exception {
        MvcResult before = mvc.perform(get(URL)).andExpect(status().isOk()).andReturn();
        Map<String, List<Map<String, Object>>> rowsBefore = storedRows();
        mvc.perform(put("/api/v1/leaves/admin/policies/1/active-version").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":12}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.activePolicyVersionId").value(12));
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(content().json(before.getResponse().getContentAsString(), JsonCompareMode.STRICT));
        assertThat(storedRows()).isEqualTo(rowsBefore);
    }

    @Test
    void returnsCurrentGroupNameAfterRenameEvenForHistoricalAssignments() throws Exception {
        Map<String, List<Map<String, Object>>> rowsBefore = storedRows();
        mvc.perform(patch("/api/v1/leaves/admin/policies/2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"이름이 바뀐 정책\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].policyId").value(2))
                .andExpect(jsonPath("$.data[2].policyName").value("이름이 바뀐 정책"));
        assertThat(storedRows()).isEqualTo(rowsBefore);
    }

    @Test
    void mapsTimestamptzAndJsonToTheSameInstantsWithoutRequiringTheOriginalOffset() throws Exception {
        jdbcTemplate.update("""
                update employee_leave_policy_assignment
                set effective_from='2000-01-01T04:00:00-05:00',effective_to='2001-01-01T18:00:00+09:00'
                where assignment_id=10
                """);
        List<EmployeeLeavePolicyAssignment> assignments = repository.findAllWithPolicyGroupByEmployeeId(42L);
        EmployeeLeavePolicyAssignment assignment = assignments.getLast();
        assertThat(assignment.getEffectiveFrom().toInstant()).isEqualTo(OffsetDateTime.parse("2000-01-01T09:00:00Z").toInstant());
        assertThat(assignment.getEffectiveTo().toInstant()).isEqualTo(OffsetDateTime.parse("2001-01-01T09:00:00Z").toInstant());
        MvcResult result = mvc.perform(get(URL)).andExpect(status().isOk()).andReturn();
        JsonNode lastAssignment = objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get(2);
        assertThat(OffsetDateTime.parse(lastAssignment.get("effectiveFrom").asString()).toInstant())
                .isEqualTo(assignment.getEffectiveFrom().toInstant());
        assertThat(OffsetDateTime.parse(lastAssignment.get("effectiveTo").asString()).toInstant())
                .isEqualTo(assignment.getEffectiveTo().toInstant());
    }

    @Test
    void usesIdDescendingAsStableTieBreakerInIsolatedRollbackFixture() {
        // 정상 스키마는 동일 직원·시작 시각을 금지하므로 격리 DB의 롤백 트랜잭션에서만 동률을 준비한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("alter table employee_leave_policy_assignment drop constraint uq_employee_policy_start");
            jdbcTemplate.update("""
                    insert into employee_leave_policy_assignment (assignment_id,emp_id,policy_id,effective_from)
                    values (31,42,2,'3000-01-01T00:00:00Z')
                    """);
            try {
                mvc.perform(get(URL)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.data[0].assignmentId").value(31))
                        .andExpect(jsonPath("$.data[1].assignmentId").value(30));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                status.setRollbackOnly();
            }
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from employee_leave_policy_assignment where assignment_id=31", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from pg_constraint where conname='uq_employee_policy_start'", Integer.class)).isEqualTo(1);
    }

    @Test
    void existingUniqueRejectsSameStartInstantAndPeriodCheckRejectsEmptyPeriod() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (emp_id,policy_id,effective_from)
                values (42,1,'3000-01-01T00:00:00Z')
                """)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (emp_id,policy_id,effective_from,effective_to)
                values (999,1,'2020-01-01T00:00:00Z','2020-01-01T00:00:00Z')
                """)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-9223372036854775808", "abc", "1.5", "9223372036854775808", "null"})
    void rejectsInvalidEmployeeIdWithExistingInputError(String employeeId) throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        mvc.perform(get(URL.replace("/42/", "/" + employeeId + "/"))).andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"success":false,"error":{"code":"COMMON_001","message":"요청 값이 올바르지 않습니다."},"data":null}
                        """, JsonCompareMode.STRICT));
        assertThat(SqlCapture.statements.get()).isEmpty();
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void rejectsNonAdminBeforeReadingHistoryOrCheckingNumericId() throws Exception {
        when(employeeAccess.isAdmin(7L)).thenReturn(false);
        mvc.perform(get(URL)).andExpect(status().isForbidden()).andExpect(content().json("""
                {"success":false,"error":{"code":"AUTH_009","message":"접근 권한이 없습니다."},"data":null}
                """, JsonCompareMode.STRICT));
        mvc.perform(get(URL.replace("/42/", "/0/"))).andExpect(status().isForbidden());
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("employee_leave_policy_assignment", "leave_policy_version", "leave_accrual_rule",
                "leave_policy_type_rule", "leave_type", "leave_request", "leave_grant", "leave_ledger")) {
            rows.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }
}
