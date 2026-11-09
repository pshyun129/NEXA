package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.policy.dto.EmployeeLeavePolicyAssignmentCreateRequest;
import com.ssafy.leaveservice.domain.policy.repository.EmployeeLeavePolicyAssignmentRepository;
import com.ssafy.leaveservice.domain.policy.service.EmployeeLeavePolicyAssignmentService;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyActivationService;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyActivationRequest;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
        "leave.test.database-owner=EmployeeLeavePolicyAssignmentCreatePostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.policy.EmployeeLeavePolicyAssignmentCreatePostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class EmployeeLeavePolicyAssignmentCreatePostgresIntegrationTest {

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

    private static final String URL = "/api/v1/leaves/admin/employees/42/policy-assignments";
    private static final OffsetDateTime FUTURE = OffsetDateTime.parse("3000-01-01T00:00:00Z");

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmployeeLeavePolicyAssignmentService service;
    @Autowired private EmployeeLeavePolicyAssignmentRepository repository;
    @Autowired private LeavePolicyActivationService activationService;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        SqlCapture.statements.get().clear();
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : List.of("leave_ledger", "leave_request", "leave_grant", "leave_accrual_rule", "leave_policy_type_rule",
                "employee_leave_policy_assignment", "leave_account", "leave_type", "leave_balance_type",
                "leave_policy_version", "leave_policy_group")) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(7L);
        when(employeeAccess.isAdmin(7L)).thenReturn(true);
        jdbcTemplate.update("insert into leave_policy_group (policy_id,name) values (1,'일반 정책'),(2,'계약직 정책'),(3,'미완성 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id,policy_id,version_no,base_type)
                values (11,1,1,'HIRE_DATE'),(21,2,1,'HIRE_DATE'),(22,2,2,'HIRE_DATE')
                """);
        jdbcTemplate.update("insert into leave_type (leave_type_id,name,balance_mode,request_unit) values (1,'연차','NONE','DAY')");
        jdbcTemplate.update("""
                insert into leave_policy_type_rule (rule_id,policy_version_id,leave_type_id)
                values (1,11,1),(2,21,1),(3,22,1)
                """);
        jdbcTemplate.update("insert into leave_accrual_rule (rule_id,policy_version_id,frequency,grant_minutes) values (1,11,'YEARLY',480)");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=21 where policy_id=2");
        jdbcTemplate.queryForObject("select setval(pg_get_serial_sequence('employee_leave_policy_assignment','assignment_id'),1000,false)", Long.class);
    }

    @Test
    void createsFirstAssignmentWithGeneratedIdAndReusesGetResponseWithoutChangingPolicySettings() throws Exception {
        Map<String, List<Map<String, Object>>> before = businessRows();
        MvcResult result = postAssignment(42, body(2, FUTURE, null));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode assignment = responseData(result);
        assertThat(assignment.get("assignmentId").asLong()).isEqualTo(1000);
        assertThat(assignment.get("employeeId").asLong()).isEqualTo(42);
        assertThat(assignment.get("policyId").asLong()).isEqualTo(2);
        assertThat(assignment.get("policyName").asString()).isEqualTo("계약직 정책");
        assertThat(assignment.size()).isEqualTo(6);
        assertThat(assignment.get("effectiveTo").isNull()).isTrue();
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].assignmentId").value(1000));
        assertThat(businessRows()).isEqualTo(before);
    }

    @Test
    void closesOnlyExistingEndAndKeepsItsIdentityWhileReturningNewAssignment() throws Exception {
        insertAssignment(100, 42, 1, "2020-01-01T00:00:00Z", null);
        Map<String, List<Map<String, Object>>> before = businessRows();
        assertThat(postAssignment(42, body(2, FUTURE, null)).getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> previous = jdbcTemplate.queryForMap("select * from employee_leave_policy_assignment where assignment_id=100");
        assertThat(previous).containsEntry("emp_id", 42L).containsEntry("policy_id", 1L);
        OffsetDateTime previousStart = jdbcTemplate.queryForObject(
                "select effective_from from employee_leave_policy_assignment where assignment_id=100", OffsetDateTime.class);
        OffsetDateTime previousEnd = jdbcTemplate.queryForObject(
                "select effective_to from employee_leave_policy_assignment where assignment_id=100", OffsetDateTime.class);
        assertThat(previousStart.toInstant())
                .isEqualTo(OffsetDateTime.parse("2020-01-01T00:00:00Z").toInstant());
        assertThat(previousEnd.toInstant()).isEqualTo(FUTURE.toInstant());
        List<String> updates = SqlCapture.statements.get().stream()
                .filter(sql -> sql.startsWith("update employee_leave_policy_assignment")).toList();
        assertThat(updates).hasSize(1);
        assertThat(updates.getFirst()).contains("set effective_to=?").doesNotContain("emp_id=", "policy_id=", "effective_from=");
        mvc.perform(get(URL)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].assignmentId").value(1000))
                .andExpect(jsonPath("$.data[1].assignmentId").value(100));
        assertThat(businessRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"policyId\":2}", "{\"policyId\":2,\"effectiveFrom\":null}"})
    void defaultsStartOnceAndUsesIdenticalInstantForCloseAndInsert(String body) throws Exception {
        insertAssignment(100, 42, 1, "2020-01-01T00:00:00Z", null);
        OffsetDateTime before = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        MvcResult result = postAssignment(42, body);
        OffsetDateTime after = OffsetDateTime.now();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        OffsetDateTime start = OffsetDateTime.parse(responseData(result).get("effectiveFrom").asString());
        assertThat(start.toInstant()).isBetween(before.toInstant(), after.toInstant());
        assertThat(start.getNano() % 1000).isZero();
        OffsetDateTime previousEnd = jdbcTemplate.queryForObject(
                "select effective_to from employee_leave_policy_assignment where assignment_id=100", OffsetDateTime.class);
        assertThat(previousEnd.toInstant()).isEqualTo(start.toInstant());
    }

    static Stream<Arguments> finitePeriods() {
        return Stream.of(
                Arguments.of("2019-01-01T00:00:00Z", "2020-01-01T00:00:00Z", true),
                Arguments.of("2020-04-01T09:00:00+09:00", "2020-05-01T00:00:00Z", true),
                Arguments.of("2020-05-01T00:00:00Z", null, true),
                Arguments.of("2018-01-01T00:00:00Z", "2018-02-01T00:00:00Z", true),
                Arguments.of("2020-03-01T00:00:00Z", "2020-05-01T00:00:00Z", false),
                Arguments.of("2020-02-01T00:00:00Z", "2020-03-01T00:00:00Z", false),
                Arguments.of("2019-01-01T00:00:00Z", "2021-01-01T00:00:00Z", false),
                Arguments.of("2020-01-01T00:00:00Z", "2020-02-01T00:00:00Z", false),
                Arguments.of("2020-02-01T00:00:00Z", null, false),
                Arguments.of("2019-12-01T00:00:00Z", "2020-02-01T00:00:00Z", false)
        );
    }

    @ParameterizedTest
    @MethodSource("finitePeriods")
    void enforcesHalfOpenPeriodsWithoutCuttingFiniteHistory(String from, String to, boolean allowed) throws Exception {
        insertAssignment(100, 42, 1, "2020-01-01T00:00:00Z", "2020-04-01T00:00:00Z");
        List<Map<String, Object>> previous = jdbcTemplate.queryForList("select *,xmin::text from employee_leave_policy_assignment where assignment_id=100");
        Map<String, List<Map<String, Object>>> before = allRows();
        MvcResult result = postAssignment(42, body(2, OffsetDateTime.parse(from), to == null ? null : OffsetDateTime.parse(to)));
        assertThat(result.getResponse().getStatus()).isEqualTo(allowed ? 201 : 409);
        assertThat(jdbcTemplate.queryForList("select *,xmin::text from employee_leave_policy_assignment where assignment_id=100")).isEqualTo(previous);
        if (!allowed) {
            assertThat(responseError(result)).isEqualTo("LEAVE_009");
            assertThat(allRows()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETROACTIVE", "SAME_POLICY", "SAME_INSTANT", "FUTURE_ALREADY_STARTED_LATER", "OTHER_FINITE_CONFLICT"})
    void rejectsForbiddenAutomaticClosureWithoutAnyPartialMutation(String state) throws Exception {
        insertAssignment(100, 42, 1, "2020-01-01T00:00:00Z", null);
        OffsetDateTime from = FUTURE;
        long policyId = 2;
        if (state.equals("RETROACTIVE")) {
            from = OffsetDateTime.parse("2021-01-01T00:00:00Z");
        } else if (state.equals("SAME_POLICY")) {
            policyId = 1;
        } else if (state.equals("SAME_INSTANT")) {
            from = OffsetDateTime.parse("2020-01-01T09:00:00+09:00");
        } else if (state.equals("FUTURE_ALREADY_STARTED_LATER")) {
            jdbcTemplate.update("update employee_leave_policy_assignment set effective_from='3001-01-01T00:00:00Z'");
        } else {
            insertAssignment(101, 42, 1, "3000-02-01T00:00:00Z", "3000-03-01T00:00:00Z");
        }
        Map<String, List<Map<String, Object>>> before = allRows();
        MvcResult result = postAssignment(42, body(policyId, from, null));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(responseError(result)).isEqualTo("LEAVE_009");
        assertThat(allRows()).isEqualTo(before);
    }

    @Test
    void allowsGapAndSamePolicyNonoverlappingHistoryAndIgnoresOtherEmployees() throws Exception {
        insertAssignment(100, 42, 1, "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z");
        insertAssignment(101, 84, 1, "2020-01-01T00:00:00Z", null);
        assertThat(postAssignment(42, body(1, FUTURE, null)).getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject("select effective_to from employee_leave_policy_assignment where assignment_id=101", OffsetDateTime.class)).isNull();
    }

    @Test
    void returnsCurrentNameAndStoredOffsetInstantsAndRejectsDuplicatePost() throws Exception {
        mvc.perform(patch("/api/v1/leaves/admin/policies/2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"최신 정책 이름\"}")).andExpect(status().isOk());
        String request = """
                {"policyId":2,"effectiveFrom":"3000-01-01T09:00:00+09:00","effectiveTo":"3001-01-01T04:00:00-05:00"}
                """;
        MvcResult result = postAssignment(42, request);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(responseData(result).get("policyName").asString()).isEqualTo("최신 정책 이름");
        assertThat(OffsetDateTime.parse(responseData(result).get("effectiveFrom").asString()).toInstant()).isEqualTo(FUTURE.toInstant());
        assertThat(OffsetDateTime.parse(responseData(result).get("effectiveTo").asString()).toInstant())
                .isEqualTo(OffsetDateTime.parse("3001-01-01T09:00:00Z").toInstant());
        Map<String, List<Map<String, Object>>> before = allRows();
        MvcResult repeated = postAssignment(42, request);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(409);
        assertThat(responseError(repeated)).isEqualTo("LEAVE_009");
        assertThat(allRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"policyId\":null}", "{\"policyId\":0}", "{\"policyId\":-1}",
            "{\"policyId\":\"2\"}", "{\"policyId\":2.0}", "{\"policyId\":true}", "{\"policyId\":[]}",
            "{\"policyId\":9223372036854775808}", "{\"policyId\":2,\"effectiveFrom\":17}",
            "{\"policyId\":2,\"effectiveFrom\":true}", "{\"policyId\":2,\"effectiveFrom\":{}}",
            "{\"policyId\":2,\"effectiveTo\":[]}", "{\"policyId\":2,\"effectiveFrom\":\"invalid\"}",
            "{\"policyId\":2,\"effectiveFrom\":\"2026-01-01\"}", "{\"policyId\":2,\"effectiveTo\":\"invalid\"}",
            "{\"policyId\":2,\"effectiveTo\":\"2000-01-01T00:00:00Z\"}",
            "{\"policyId\":2,\"effectiveFrom\":\"3000-01-01T00:00:00Z\",\"effectiveTo\":\"3000-01-01T09:00:00+09:00\"}",
            "{\"policyId\":2,\"effectiveFrom\":\"3000-01-01T00:00:00Z\",\"effectiveTo\":\"2999-01-01T00:00:00Z\"}",
            "{\"policyId\":2,\"effectiveFrom\":\"3000-01-01T00:00:00.0000001Z\",\"effectiveTo\":\"3000-01-01T00:00:00.0000002Z\"}"})
    void rejectsInvalidBodyBeforeAnyWrite(String body) throws Exception {
        Map<String, List<Map<String, Object>>> before = allRows();
        MvcResult result = postAssignment(42, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(responseError(result)).isEqualTo("COMMON_001");
        assertThat(allRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidEmployeeId(String employeeId) throws Exception {
        mvc.perform(post(URL.replace("/42/", "/" + employeeId + "/")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(2, FUTURE, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {3, 999})
    void rejectsMissingOrInactiveGroupWithoutWrites(long policyId) throws Exception {
        Map<String, List<Map<String, Object>>> before = allRows();
        MvcResult result = postAssignment(42, body(policyId, FUTURE, null));
        assertThat(result.getResponse().getStatus()).isEqualTo(policyId == 3 ? 409 : 404);
        assertThat(responseError(result)).isEqualTo(policyId == 3 ? "LEAVE_009" : "LEAVE_004");
        assertThat(allRows()).isEqualTo(before);
    }

    @Test
    void rejectsDeniedAdminWithoutAcquiringLocks() throws Exception {
        when(employeeAccess.isAdmin(7L)).thenReturn(false);
        MvcResult result = postAssignment(42, body(2, FUTURE, null));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(responseError(result)).isEqualTo("AUTH_009");
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @Test
    void failedInsertRollsBackAlreadyFlushedAutomaticClosure() throws Exception {
        insertAssignment(100, 42, 1, "2020-01-01T00:00:00Z", null);
        jdbcTemplate.execute("alter table employee_leave_policy_assignment add constraint test_assignment_insert_failure check (policy_id <> 2)");
        try {
            Map<String, List<Map<String, Object>>> before = allRows();
            MvcResult result = postAssignment(42, body(2, FUTURE, null));
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(responseError(result)).isEqualTo("COMMON_002");
            assertThat(SqlCapture.statements.get()).anyMatch(sql -> sql.startsWith("update employee_leave_policy_assignment"));
            assertThat(allRows()).isEqualTo(before);
        } finally {
            jdbcTemplate.execute("alter table employee_leave_policy_assignment drop constraint test_assignment_insert_failure");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstConcurrentPostsWaitForEmployeeLockAndValidateLatestRows(boolean identicalStart) throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> second = new TransactionTemplate(transactionManager).execute(status -> {
                service.createPolicyAssignment(42L, new EmployeeLeavePolicyAssignmentCreateRequest(1L, FUTURE, FUTURE.plusYears(2)));
                OffsetDateTime secondStart = identicalStart ? FUTURE : FUTURE.plusYears(1);
                Future<MvcResult> pending = executor.submit(() -> postAssignment(42, body(2, secondStart, FUTURE.plusYears(3))));
                waitForLock("%pg_advisory_xact_lock%");
                return pending;
            });
            assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from employee_leave_policy_assignment where emp_id=42", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSwitchesDoNotCloseOrOverwriteTheWinningNewAssignment() throws Exception {
        insertAssignment(100, 42, 1, "2020-01-01T00:00:00Z", null);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> second = new TransactionTemplate(transactionManager).execute(status -> {
                service.createPolicyAssignment(42L, new EmployeeLeavePolicyAssignmentCreateRequest(2L, FUTURE, null));
                Future<MvcResult> pending = executor.submit(() -> postAssignment(42, body(1, FUTURE, null)));
                waitForLock("%pg_advisory_xact_lock%");
                return pending;
            });
            assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from employee_leave_policy_assignment where emp_id=42", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select effective_to from employee_leave_policy_assignment where assignment_id=1000", OffsetDateTime.class)).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {84, Long.MAX_VALUE})
    void differentEmployeesCanAssignTheSameGroupBeforeTheOtherTransactionCommits(long otherEmployee) {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                service.createPolicyAssignment(42L, new EmployeeLeavePolicyAssignmentCreateRequest(1L, FUTURE, null));
                try {
                    MvcResult independent = executor.submit(() -> postAssignment(otherEmployee, body(1, FUTURE, null)))
                            .get(10, TimeUnit.SECONDS);
                    assertThat(independent.getResponse().getStatus()).isEqualTo(201);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from employee_leave_policy_assignment", Integer.class)).isEqualTo(2);
    }

    @Test
    void transactionRollbackReleasesEmployeeLockForWaitingFirstAssignment() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> result = new TransactionTemplate(transactionManager).execute(status -> {
                repository.lockEmployeeAssignments(42L);
                Future<MvcResult> pending = executor.submit(() -> postAssignment(42, body(2, FUTURE, null)));
                waitForLock("%pg_advisory_xact_lock%");
                status.setRollbackOnly();
                return pending;
            });
            assertThat(result.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        }
    }

    @Test
    void activationFirstMakesAssignmentWaitForLatestActivePointer() throws Exception {
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null where policy_id=2");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> assignment = new TransactionTemplate(transactionManager).execute(status -> {
                activationService.activateVersion(2L, new LeavePolicyActivationRequest(22L));
                Future<MvcResult> pending = executor.submit(() -> postAssignment(42, body(2, FUTURE, null)));
                waitForLock("%leave_policy_group%");
                return pending;
            });
            assertThat(assignment.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        }
    }

    @Test
    void assignmentFirstMakesActivationWaitWithoutBindingAssignmentToOldVersion() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> activation = new TransactionTemplate(transactionManager).execute(status -> {
                service.createPolicyAssignment(42L, new EmployeeLeavePolicyAssignmentCreateRequest(2L, FUTURE, null));
                Future<MvcResult> pending = executor.submit(() -> mvc.perform(put("/api/v1/leaves/admin/policies/2/active-version")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"versionId\":22}")).andReturn());
                waitForLock("%leave_policy_group%");
                return pending;
            });
            assertThat(activation.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(jdbcTemplate.queryForObject("select active_policy_version_id from leave_policy_group where policy_id=2", Long.class)).isEqualTo(22);
        assertThat(jdbcTemplate.queryForObject("select policy_id from employee_leave_policy_assignment where emp_id=42", Long.class)).isEqualTo(2);
    }

    private MvcResult postAssignment(long employeeId, String body) throws Exception {
        return mvc.perform(post(URL.replace("/42/", "/" + employeeId + "/")).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String body(long policyId, OffsetDateTime from, OffsetDateTime to) {
        return "{\"policyId\":" + policyId + ",\"effectiveFrom\":\"" + from + "\",\"effectiveTo\":"
                + (to == null ? "null" : "\"" + to + "\"") + "}";
    }

    private JsonNode responseData(MvcResult result) {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(response.get("success").asBoolean()).isTrue();
        assertThat(response.get("error").isNull()).isTrue();
        return response.get("data");
    }

    private String responseError(MvcResult result) {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("data").isNull()).isTrue();
        return response.get("error").get("code").asString();
    }

    private void insertAssignment(long id, long employee, long policy, String from, String to) {
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (assignment_id,emp_id,policy_id,effective_from,effective_to)
                values (?,?,?,cast(? as timestamptz),cast(? as timestamptz))
                """, id, employee, policy, from, to);
    }

    private Map<String, List<Map<String, Object>>> businessRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("leave_policy_group", "leave_policy_version", "leave_accrual_rule", "leave_policy_type_rule",
                "leave_type", "leave_request", "leave_grant", "leave_ledger")) {
            rows.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }

    private Map<String, List<Map<String, Object>>> allRows() {
        Map<String, List<Map<String, Object>>> rows = businessRows();
        rows.put("employee_leave_policy_assignment", jdbcTemplate.queryForList(
                "select *,xmin::text as row_version from employee_leave_policy_assignment order by assignment_id"));
        return rows;
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
