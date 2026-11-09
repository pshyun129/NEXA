package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.policy.dto.LeaveAccrualRuleReplaceRequest;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.domain.policy.service.LeaveAccrualRuleService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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
        "leave.test.database-owner=LeaveAccrualRuleReplacePostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeaveAccrualRuleReplacePostgresIntegrationTest {

    private static final String VERSION_URL = "/api/v1/leaves/admin/policies/1/versions/14";
    private static final String URL = VERSION_URL + "/accrual-rules";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LeaveAccrualRuleService service;

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
        for (String table : List.of("leave_ledger", "leave_request", "leave_grant", "leave_accrual_rule",
                "leave_account", "leave_type", "leave_balance_type", "leave_policy_version", "leave_policy_group")) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '일반 정책'), (2, '다른 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id, policy_id, version_no, base_type)
                values (11, 1, 1, 'HIRE_DATE'), (14, 1, 2, 'HIRE_DATE'), (15, 1, 3, 'HIRE_DATE'), (21, 2, 1, 'HIRE_DATE')
                """);
        jdbcTemplate.update("""
                insert into leave_accrual_rule
                    (rule_id, policy_version_id, min_service_months, max_service_months, frequency, grant_minutes, is_active)
                values (40, 14, 0, 11, 'MONTHLY', 480, true), (41, 14, 12, null, 'YEARLY', 7200, true),
                       (42, 14, 60, null, 'CUSTOM', 15, false), (50, 15, 0, null, 'ON_HIRE', 60, true),
                       (60, 21, 0, null, 'YEARLY', 960, true)
                """);
        jdbcTemplate.queryForObject("select setval(pg_get_serial_sequence('leave_accrual_rule','rule_id'),100,false)", Long.class);
        jdbcTemplate.update("insert into leave_type (leave_type_id, name, balance_mode) values (1, '특별 휴가', 'NONE')");
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id, name) values (1, '연차')");
        jdbcTemplate.update("insert into leave_account (account_id, emp_id, balance_type_id) values (1, 42, 1)");
    }

    @Test
    void insertsRulesIntoEmptyVersionAndPreservesIdsOnRetryWithoutIds() throws Exception {
        String url = "/api/v1/leaves/admin/policies/1/versions/11/accrual-rules";
        String body = request(rule(null, 0, 11, "MONTHLY", 17, true), rule(null, 12, null, "YEARLY", 7200, false));

        MvcResult first = mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].ruleId").value(100))
                .andExpect(jsonPath("$.data[1].ruleId").value(101)).andReturn();
        List<Map<String, Object>> before = rowsWithTransactionIds();

        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().json(first.getResponse().getContentAsString(), JsonCompareMode.STRICT));
        assertThat(rowsWithTransactionIds()).isEqualTo(before);
        mvc.perform(get(url)).andExpect(content().json(first.getResponse().getContentAsString(), JsonCompareMode.STRICT));
    }

    @Test
    void updatesInPlaceAddsAndDeletesOnlyRequestedVersionRules() throws Exception {
        List<Map<String, Object>> otherRules = jdbcTemplate.queryForList("select * from leave_accrual_rule where policy_version_id <> 14 order by rule_id");
        String body = request(rule(40L, 0, 11, "MONTHLY", 17, false),
                rule(41L, 12, null, "YEARLY", 7200, true), rule(null, 0, null, "ON_HIRE", 1, true));

        MvcResult result = mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].ruleId").value(40)).andExpect(jsonPath("$.data[0].grantMinutes").value(17))
                .andExpect(jsonPath("$.data[0].isActive").value(false))
                .andExpect(jsonPath("$.data[1].ruleId").value(41)).andExpect(jsonPath("$.data[2].ruleId").value(100)).andReturn();

        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_accrual_rule where rule_id = 42", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForList("select * from leave_accrual_rule where policy_version_id <> 14 order by rule_id")).isEqualTo(otherRules);
        mvc.perform(get(URL)).andExpect(content().json(result.getResponse().getContentAsString(), JsonCompareMode.STRICT));
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(content().json(result.getResponse().getContentAsString(), JsonCompareMode.STRICT));
    }

    @Test
    void updatesSeveralExistingRulesWithoutRecreatingIds() throws Exception {
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request(
                        rule(42L, 60, null, "CUSTOM", 21, true), rule(41L, 12, null, "YEARLY", 7000, false),
                        rule(40L, 0, 11, "MONTHLY", 17, true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].ruleId").value(40))
                .andExpect(jsonPath("$.data[1].ruleId").value(41)).andExpect(jsonPath("$.data[2].ruleId").value(42));
    }

    @Test
    void reservesExplicitIdsBeforeMatchingIdlessRulesRegardlessOfRequestOrder() throws Exception {
        String body = request(rule(null, 0, 11, "MONTHLY", 480, true), rule(40L, 1, 11, "MONTHLY", 17, true));

        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].ruleId").value(40))
                .andExpect(jsonPath("$.data[0].grantMinutes").value(17)).andExpect(jsonPath("$.data[1].ruleId").value(100));
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[1].ruleId").value(100));
    }

    @Test
    void emptyListRemovesAllAndRepeatedEmptyListIsNoopEvenWhenActive() throws Exception {
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}", JsonCompareMode.STRICT));
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 14 where policy_id = 1");
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isOk());
    }

    @Test
    void permitsOverlappingRangesAndDifferentFrequencies() throws Exception {
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request(
                        rule(null, 0, null, "MONTHLY", 480, true), rule(null, 0, null, "YEARLY", 7200, true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void refusesAmbiguousLegacyMatchesWithoutCleaningUpDuplicates() throws Exception {
        jdbcTemplate.update("""
                insert into leave_accrual_rule (rule_id, policy_version_id, min_service_months, max_service_months, frequency, grant_minutes, is_active)
                values (43, 14, 0, 11, 'MONTHLY', 480, true)
                """);
        List<Map<String, Object>> before = rowsWithTransactionIds();

        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request(rule(null, 0, 11, "MONTHLY", 480, true))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
        assertThat(rowsWithTransactionIds()).isEqualTo(before);
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request(rule(43L, 0, 11, "MONTHLY", 480, true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].ruleId").value(43));
    }

    static Stream<String> invalidBodies() {
        return Stream.of("{}", "{\"rules\":null}", "{\"rules\":{}}", "{\"rules\":1}", "{\"rules\":[null]}", "{\"rules\":[1]}",
                request(rule(40L, 0, 11, "MONTHLY", 480, true), rule(40L, 0, 11, "MONTHLY", 17, true)),
                request(rule(null, 0, null, "YEARLY", 480, true), rule(null, 0, null, "YEARLY", 480, true)),
                request(rule(40L, 0, null, "YEARLY", 480, true), rule(41L, 0, null, "YEARLY", 480, true)));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidListAndDuplicatesWithoutMutating(String body) throws Exception {
        List<Map<String, Object>> before = rowsWithTransactionIds();
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(rowsWithTransactionIds()).isEqualTo(before);
    }

    static Stream<String> invalidFields() {
        return Stream.of("minServiceMonths:<omitted>", "minServiceMonths:null", "minServiceMonths:-1", "minServiceMonths:1.5",
                "maxServiceMonths:-1", "frequency:<omitted>", "frequency:null", "frequency:\"UNKNOWN\"", "frequency:0",
                "grantMinutes:<omitted>", "grantMinutes:null", "grantMinutes:0", "grantMinutes:-1", "grantMinutes:\"30\"",
                "isActive:<omitted>", "isActive:null", "isActive:\"true\"");
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void rejectsIncompleteOrInvalidItemWithoutSavingOtherValidItems(String invalidField) throws Exception {
        String[] parts = invalidField.split(":", 2);
        ObjectNode invalidRule = (ObjectNode) objectMapper.readTree(rule(null, 0, 11, "MONTHLY", 480, true));
        if (parts[1].equals("<omitted>")) {
            invalidRule.remove(parts[0]);
        } else {
            invalidRule.set(parts[0], objectMapper.readTree(parts[1]));
        }
        List<Map<String, Object>> before = rowsWithTransactionIds();

        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request(
                        rule(40L, 0, 11, "MONTHLY", 17, true), objectMapper.writeValueAsString(invalidRule))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(rowsWithTransactionIds()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(longs = {9999L, 50L, 60L})
    void rejectsMissingOrForeignRuleIdsWithSame404(Long ruleId) throws Exception {
        List<Map<String, Object>> before = rowsWithTransactionIds();
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request(rule(ruleId, 0, null, "YEARLY", 480, true))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_007"));
        assertThat(rowsWithTransactionIds()).isEqualTo(before);
    }

    @Test
    void preservesAuthenticationGroupAndVersionErrorContracts() throws Exception {
        mvc.perform(put("/api/v1/leaves/admin/policies/9999/versions/14/accrual-rules")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_004"));
        for (long id : List.of(9999L, 21L)) {
            mvc.perform(put("/api/v1/leaves/admin/policies/1/versions/" + id + "/accrual-rules")
                            .contentType(MediaType.APPLICATION_JSON).content(request()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_005"));
        }
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DRAFT", "CANCELLED", "REJECTED", "GRANT", "RULE_GRANT", "EXPIRED_CONSUMED"})
    void protectsActualChangesButAcceptsReorderedIdlessNoop(String usage) throws Exception {
        addUsage(usage);
        List<Map<String, Object>> before = rowsWithTransactionIds();
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_006"));
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request(
                        rule(null, 60, null, "CUSTOM", 15, false), rule(null, 12, null, "YEARLY", 7200, true),
                        rule(null, 0, 11, "MONTHLY", 480, true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].ruleId").value(40))
                .andExpect(jsonPath("$.data[2].ruleId").value(42));
        assertThat(rowsWithTransactionIds()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADD", "UPDATE", "ACTIVE_FLAG"})
    void activeVersionRejectsEveryKindOfActualChange(String action) throws Exception {
        addUsage("ACTIVE");
        String first = rule(40L, 0, 11, "MONTHLY", action.equals("UPDATE") ? 17 : 480, !action.equals("ACTIVE_FLAG"));
        String body = action.equals("ADD")
                ? request(first, rule(41L, 12, null, "YEARLY", 7200, true), rule(42L, 60, null, "CUSTOM", 15, false), rule(null, 0, null, "ON_HIRE", 1, true))
                : request(first, rule(41L, 12, null, "YEARLY", 7200, true), rule(42L, 60, null, "CUSTOM", 15, false));
        List<Map<String, Object>> before = rowsWithTransactionIds();
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_006"));
        assertThat(rowsWithTransactionIds()).isEqualTo(before);
    }

    @Test
    void rollsBackUpdatesAndInsertsWhenOmittedRuleDeletionFailsForeignKey() throws Exception {
        List<Map<String, Object>> before = rowsWithTransactionIds();
        jdbcTemplate.execute("create table put_test_reference (rule_id bigint references leave_accrual_rule)");
        try {
            jdbcTemplate.update("insert into put_test_reference values (42)");
            mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request(
                            rule(40L, 0, 11, "MONTHLY", 17, true), rule(null, 0, null, "ON_HIRE", 1, true))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
            assertThat(rowsWithTransactionIds()).isEqualTo(before);
        } finally {
            jdbcTemplate.execute("drop table put_test_reference");
        }
    }

    @Test
    void concurrentIdenticalPutsReuseNewlyCommittedIds() throws Exception {
        String body = request(rule(null, 0, null, "ON_HIRE", 17, true));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> concurrentPut(body, ready, start));
            Future<MvcResult> second = executor.submit(() -> concurrentPut(body, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult firstResult = first.get(15, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(15, TimeUnit.SECONDS);
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(objectMapper.readTree(firstResult.getResponse().getContentAsString()))
                    .isEqualTo(objectMapper.readTree(secondResult.getResponse().getContentAsString()));
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_accrual_rule where policy_version_id = 14", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE"})
    void waitsForVersionMutationAndRechecksExistence(String action) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> putResult = transaction.execute(status -> {
                groupRepository.findByIdForUpdate(1L).orElseThrow();
                mutateVersion(action);
                Future<MvcResult> result = executor.submit(() -> mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(request())).andReturn());
                waitForLock("%leave_policy_group%");
                return result;
            });
            assertThat(putResult.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(action.equals("DELETE") ? 404 : 200);
        }
        if (action.equals("PATCH")) {
            assertThat(jdbcTemplate.queryForObject("select unit_minutes from leave_policy_version where policy_version_id = 14", Integer.class)).isEqualTo(17);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "DELETE"})
    void earlierGrantReferenceCommitsBeforePutAndPreventsMutation(String action) throws Exception {
        List<Map<String, Object>> before = rowsWithTransactionIds();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> result = transaction.execute(status -> {
                addUsage("RULE_GRANT");
                String body = action.equals("DELETE") ? request() : request(rule(40L, 0, 11, "MONTHLY", 17, true));
                Future<MvcResult> response = executor.submit(() -> mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());
                waitForLock("%leave_policy_version%");
                return response;
            });
            assertThat(result.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(rowsWithTransactionIds()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "DELETE"})
    void laterGrantWaitsForPutAndMustReReadSettingsAfterCommit(String action) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> reference = transaction.execute(status -> {
                groupRepository.findByIdForUpdate(1L).orElseThrow();
                versionRepository.findByIdAndPolicyIdForDelete(14L, 1L).orElseThrow();
                Future<?> result = executor.submit(() -> addUsage("RULE_GRANT"));
                waitForLock("%insert into leave_grant%");
                String body = action.equals("DELETE") ? request() : request(rule(40L, 0, 11, "MONTHLY", 17, true));
                try {
                    service.replaceAccrualRules(1L, 14L, objectMapper.readValue(body, LeaveAccrualRuleReplaceRequest.class));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return result;
            });
            if (action.equals("DELETE")) {
                assertThatThrownBy(() -> reference.get(15, TimeUnit.SECONDS)).hasCauseInstanceOf(DataIntegrityViolationException.class);
            } else {
                reference.get(15, TimeUnit.SECONDS);
                assertThat(jdbcTemplate.queryForObject("select grant_minutes from leave_accrual_rule where rule_id = 40", Integer.class)).isEqualTo(17);
                // FK는 UPDATE 후에도 유효하므로 오래된 480분 계산값 저장 자체는 차단하지 못한다.
                assertThat(jdbcTemplate.queryForObject("select original_minutes from leave_grant where rule_id = 40", Integer.class)).isEqualTo(480);
            }
        }
    }

    private void mutateVersion(String action) {
        try {
            MockHttpServletRequestBuilder mutation = action.equals("DELETE") ? delete(VERSION_URL)
                    : patch(VERSION_URL).contentType(MediaType.APPLICATION_JSON).content("{\"unitMinutes\":17}");
            assertThat(mvc.perform(mutation).andReturn().getResponse().getStatus()).isEqualTo(200);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MvcResult concurrentPut(String body, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 요청 대기 시간 초과");
        }
        return mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
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
        fail("PostgreSQL 잠금 대기 확인 실패: " + queryPattern);
    }

    private List<Map<String, Object>> rowsWithTransactionIds() {
        return jdbcTemplate.queryForList("select *, xmin::text as row_transaction_id from leave_accrual_rule order by rule_id");
    }

    private void addUsage(String usage) {
        if (usage.equals("ACTIVE")) {
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 14 where policy_id = 1");
        } else if (usage.equals("GRANT") || usage.equals("RULE_GRANT") || usage.equals("EXPIRED_CONSUMED")) {
            Long ruleId = usage.equals("RULE_GRANT") ? 40L : null;
            jdbcTemplate.update("""
                    insert into leave_grant (grant_id, account_id, policy_version_id, rule_id, grant_kind, original_minutes, valid_from, expires_on)
                    values (14, 1, 14, ?, 'AUTO', 480, '2020-01-01T00:00:00Z', '2021-01-01T00:00:00Z')
                    """, ruleId);
            if (usage.equals("EXPIRED_CONSUMED")) {
                jdbcTemplate.update("""
                        insert into leave_ledger (account_id, grant_id, event_type, quantity_delta, idempotency_key)
                        values (1,14,'GRANT',480,'grant-14'),(1,14,'CONSUME',-480,'consume-14')
                        """);
            }
        } else {
            jdbcTemplate.update("insert into leave_request (policy_version_id, leave_type_id, emp_id, status) values (14,1,42,?)", usage);
        }
    }

    private static String request(String... rules) {
        return "{\"rules\":[" + String.join(",", rules) + "]}";
    }

    private static String rule(
            Long ruleId,
            int minServiceMonths,
            Integer maxServiceMonths,
            String frequency,
            int grantMinutes,
            boolean active
    ) {
        return """
                {"ruleId":%s,"minServiceMonths":%d,"maxServiceMonths":%s,"frequency":"%s","grantMinutes":%d,"isActive":%s}
                """.formatted(ruleId, minServiceMonths, maxServiceMonths, frequency, grantMinutes, active);
    }
}
