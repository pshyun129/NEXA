package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyVersionService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=LeavePolicyVersionDeletePostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeavePolicyVersionDeletePostgresIntegrationTest {

    private static final String URL = "/api/v1/leaves/admin/policies/1/versions/13";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LeavePolicyVersionService service;

    @Autowired
    private LeavePolicyGroupRepository groupRepository;

    @Autowired
    private LeavePolicyVersionRepository versionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = null");
        for (String table : List.of("leave_ledger", "leave_request", "leave_grant", "leave_policy_type_rule",
                "leave_accrual_rule", "employee_leave_policy_assignment", "leave_account", "leave_type",
                "leave_balance_type", "leave_policy_version", "leave_policy_group")) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '일반 정책'), (2, '다른 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id, policy_id, version_no, base_type)
                values (11, 1, 1, 'HIRE_DATE'), (13, 1, 2, 'HIRE_DATE'), (21, 2, 1, 'HIRE_DATE')
                """);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 11 where policy_id = 1");
        jdbcTemplate.update("insert into leave_type (leave_type_id, name, balance_mode) values (1, '특별 휴가', 'NONE')");
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id, name) values (1, '연차')");
        jdbcTemplate.update("insert into leave_account (account_id, emp_id, balance_type_id) values (1, 42, 1)");
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (emp_id, policy_id, effective_from)
                values (42, 1, '2026-01-01T00:00:00Z')
                """);
        addRules(21L, true, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "ACCRUAL", "TYPE", "BOTH"})
    void deletesUnusedVersionAndOnlyItsRules(String rules) throws Exception {
        addRules(13L, rules.equals("ACCRUAL") || rules.equals("BOTH"), rules.equals("TYPE") || rules.equals("BOTH"));
        List<Map<String, Object>> otherVersion = jdbcTemplate.queryForList("select * from leave_policy_version where policy_version_id <> 13 order by policy_version_id");
        List<Map<String, Object>> groups = jdbcTemplate.queryForList("select * from leave_policy_group order by policy_id");
        List<Map<String, Object>> assignments = jdbcTemplate.queryForList("select * from employee_leave_policy_assignment");

        mvc.perform(delete(URL)).andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"error\":null,\"data\":null}", JsonCompareMode.STRICT));

        assertTargetDeleted();
        assertThat(jdbcTemplate.queryForList("select * from leave_policy_version order by policy_version_id")).isEqualTo(otherVersion);
        assertThat(jdbcTemplate.queryForList("select * from leave_policy_group order by policy_id")).isEqualTo(groups);
        assertThat(jdbcTemplate.queryForList("select * from employee_leave_policy_assignment")).isEqualTo(assignments);
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_accrual_rule where policy_version_id = 21", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_type_rule where policy_version_id = 21", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/leaves/admin/policies/1/versions")).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(URL)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_005"));
        mvc.perform(delete(URL)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_005"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DRAFT", "CANCELLED", "REJECTED", "GRANT", "RULE_GRANT", "EXPIRED_CONSUMED"})
    void preservesVersionRulesAndAllHistoryWhenDeletionIsForbidden(String usage) throws Exception {
        addRules(13L, true, true);
        addUsage(usage);
        Map<String, List<Map<String, Object>>> before = protectedRows();

        mvc.perform(delete(URL)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_006"));

        assertThat(protectedRows()).isEqualTo(before);
    }

    @Test
    void rejectsMissingGroupMissingVersionOtherGroupAndNonAdmin() throws Exception {
        mvc.perform(delete("/api/v1/leaves/admin/policies/9999/versions/13"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_004"));
        for (long versionId : List.of(9999L, 21L)) {
            mvc.perform(delete("/api/v1/leaves/admin/policies/1/versions/" + versionId))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_005"));
        }
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        mvc.perform(delete(URL)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_version", Integer.class)).isEqualTo(3);
    }

    @Test
    void rollsBackChildDeletesWhenFinalVersionDeleteHitsForeignKey() throws Exception {
        addRules(13L, true, true);
        Map<String, List<Map<String, Object>>> before = protectedRows();
        // 격리 컨테이너에만 추가한 FK로 마지막 삭제 단계의 실패를 유발한다.
        jdbcTemplate.execute("create table delete_test_reference (policy_version_id bigint references leave_policy_version)");
        try {
            jdbcTemplate.update("insert into delete_test_reference values (13)");

            mvc.perform(delete(URL)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));

            assertThat(protectedRows()).isEqualTo(before);
        } finally {
            jdbcTemplate.execute("drop table delete_test_reference");
        }
    }

    @Test
    void reusesHighestDeletedVersionNumberWithoutRenumberingOthers() throws Exception {
        mvc.perform(delete(URL)).andExpect(status().isOk());
        jdbcTemplate.queryForObject("select setval(pg_get_serial_sequence('leave_policy_version','policy_version_id'),100,false)", Long.class);

        mvc.perform(post("/api/v1/leaves/admin/policies/1/versions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseType\":\"HIRE_DATE\",\"unitMinutes\":30,\"carryOverAllowed\":false}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.versionNo").value(2));
        assertThat(jdbcTemplate.queryForObject("select version_no from leave_policy_version where policy_version_id = 11", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDeletesReturnOneSuccessAndOneNotFound() throws Exception {
        addRules(13L, true, true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> concurrentRequest(false, ready, start));
            Future<Integer> second = executor.submit(() -> concurrentRequest(false, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 404);
        }
        assertTargetDeleted();
    }

    @Test
    void patchAndDeleteSerializeWithoutLeavingPartialRules() throws Exception {
        addRules(13L, true, true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> patchResult = executor.submit(() -> concurrentRequest(true, ready, start));
            Future<Integer> deleteResult = executor.submit(() -> concurrentRequest(false, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(deleteResult.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(patchResult.get(15, TimeUnit.SECONDS)).isIn(200, 404);
        }
        assertTargetDeleted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "RULE_GRANT"})
    void waitsForEarlierReferenceAndRejectsDeleteAfterReferenceCommits(String usage) throws Exception {
        addRules(13L, true, true);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> deletion = transaction.execute(status -> {
                addUsage(usage);
                Future<MvcResult> result = executor.submit(() -> mvc.perform(delete(URL)).andReturn());
                waitForLock("%leave_policy_version%");
                return result;
            });

            assertThat(deletion.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_version where policy_version_id = 13", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_accrual_rule where policy_version_id = 13", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "RULE_GRANT"})
    void laterReferenceWaitsForDeleteAndFailsForeignKeyAfterCommit(String usage) throws Exception {
        addRules(13L, true, true);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> reference = transaction.execute(status -> {
                // DELETE 문 실행 전의 명시적 행 잠금만으로도 FK 확인이 대기하는지 검증한다.
                groupRepository.findByIdForUpdate(1L).orElseThrow();
                versionRepository.findByIdAndPolicyIdForDelete(13L, 1L).orElseThrow();
                Future<?> result = executor.submit(() -> addUsage(usage));
                waitForLock(usage.equals("DRAFT") ? "%insert into leave_request%" : "%insert into leave_grant%");
                service.deletePolicyVersion(1L, 13L);
                return result;
            });

            assertThatThrownBy(() -> reference.get(15, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(DataIntegrityViolationException.class);
        }
        assertTargetDeleted();
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_request", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_grant", Integer.class)).isZero();
    }

    @Test
    void waitsForActivationAndPreservesNewActivePointer() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> deletion = transaction.execute(status -> {
                groupRepository.findByIdForUpdate(1L).orElseThrow();
                Future<MvcResult> result = executor.submit(() -> mvc.perform(delete(URL)).andReturn());
                waitForLock("%leave_policy_group%");
                jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 13 where policy_id = 1");
                return result;
            });
            assertThat(deletion.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(groupRepository.findById(1L).orElseThrow().getActivePolicyVersionId()).isEqualTo(13L);
    }

    private int concurrentRequest(boolean update, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 요청 시작 대기 시간 초과");
        }
        if (update) {
            return mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("{\"unitMinutes\":17}"))
                    .andReturn().getResponse().getStatus();
        }
        return mvc.perform(delete(URL)).andReturn().getResponse().getStatus();
    }

    private void waitForLock(String queryPattern) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            jdbcTemplate.execute("select pg_stat_clear_snapshot()");
            Integer waiters = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity where datname = current_database()
                    and pid <> pg_backend_pid() and wait_event_type = 'Lock' and query like ?
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
        fail("PostgreSQL에서 잠금 대기를 확인하지 못했습니다: " + queryPattern);
    }

    private void assertTargetDeleted() {
        for (String table : List.of("leave_policy_version", "leave_accrual_rule", "leave_policy_type_rule")) {
            assertThat(jdbcTemplate.queryForObject("select count(*) from " + table + " where policy_version_id = 13", Integer.class)).isZero();
        }
    }

    private Map<String, List<Map<String, Object>>> protectedRows() {
        Map<String, List<Map<String, Object>>> rows = new java.util.LinkedHashMap<>();
        for (String table : List.of("leave_policy_group", "leave_policy_version", "leave_accrual_rule", "leave_policy_type_rule",
                "leave_request", "leave_grant", "leave_ledger")) {
            rows.put(table, jdbcTemplate.queryForList("select * from " + table + " order by 1"));
        }
        return rows;
    }

    private void addRules(long versionId, boolean accrual, boolean type) {
        if (accrual) {
            jdbcTemplate.update("""
                    insert into leave_accrual_rule (rule_id, policy_version_id, frequency, grant_minutes)
                    values (?, ?, 'YEARLY', 480)
                    """, versionId, versionId);
        }
        if (type) {
            jdbcTemplate.update("insert into leave_policy_type_rule (rule_id, policy_version_id, leave_type_id) values (?, ?, 1)", versionId, versionId);
        }
    }

    private void addUsage(String usage) {
        if (usage.equals("ACTIVE")) {
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 13 where policy_id = 1");
        } else if (usage.equals("GRANT") || usage.equals("RULE_GRANT") || usage.equals("EXPIRED_CONSUMED")) {
            Long ruleId = usage.equals("RULE_GRANT") ? 13L : null;
            jdbcTemplate.update("""
                    insert into leave_grant (grant_id, account_id, policy_version_id, rule_id, grant_kind,
                                             original_minutes, valid_from, expires_on)
                    values (13, 1, 13, ?, 'AUTO', 480, '2020-01-01T00:00:00Z', '2021-01-01T00:00:00Z')
                    """, ruleId);
            if (usage.equals("EXPIRED_CONSUMED")) {
                jdbcTemplate.update("""
                        insert into leave_ledger (account_id, grant_id, event_type, quantity_delta, idempotency_key)
                        values (1, 13, 'GRANT', 480, 'grant-13'), (1, 13, 'CONSUME', -480, 'consume-13')
                        """);
            }
        } else {
            jdbcTemplate.update("insert into leave_request (policy_version_id, leave_type_id, emp_id, status) values (13, 1, 42, ?)", usage);
        }
    }
}
