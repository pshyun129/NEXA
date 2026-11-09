package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
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
        "leave.test.database-owner=LeavePolicyVersionPatchPostgresIntegrationTest",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/",
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeavePolicyVersionPatchPostgresIntegrationTest {

    private static final String URL = "/api/v1/leaves/admin/policies/1/versions/13";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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
        for (String table : List.of("leave_request", "leave_grant", "leave_policy_type_rule", "leave_accrual_rule",
                "leave_account", "leave_type", "leave_balance_type", "leave_policy_version", "leave_policy_group")) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '일반 정책'), (2, '다른 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version
                    (policy_version_id, policy_id, version_no, base_type, fiscal_start, unit_minutes,
                     carry_over_allowed, carry_over_limit_minutes, expiry_rule, effective_from, effective_to, created_at)
                values (13, 1, 2, 'FISCAL_YEAR', '01-01', 60, true, 240, 'CUSTOM_RULE',
                        '2026-01-01T00:00:00Z', '2027-01-01T00:00:00Z', '2026-10-21T04:00:00Z'),
                       (11, 1, 1, 'HIRE_DATE', null, 30, false, null, 'END_OF_VALIDITY', null, null, '2026-10-21T04:00:00Z'),
                       (21, 2, 1, 'HIRE_DATE', null, 30, false, null, 'END_OF_VALIDITY', null, null, '2026-10-21T04:00:00Z')
                """);
        jdbcTemplate.update("insert into leave_type (leave_type_id, name, balance_mode) values (1, '특별 휴가', 'NONE')");
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id, name) values (1, '연차')");
        jdbcTemplate.update("insert into leave_account (account_id, emp_id, balance_type_id) values (1, 42, 1)");
    }

    @Test
    void updatesAllSettingsWithoutChangingIdentityOrActivePointer() throws Exception {
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 11 where policy_id = 1");
        Map<String, Object> before = versionRow();

        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"baseType":"HIRE_DATE","fiscalStart":null,"unitMinutes":17,"carryOverAllowed":false,
                         "carryOverLimitMinutes":null,"expiryRule":"END_OF_VALIDITY","effectiveFrom":null,"effectiveTo":null}
                        """))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"error":null,"data":{
                          "policyVersionId":13,"policyId":1,"versionNo":2,"baseType":"HIRE_DATE","fiscalStart":null,
                          "unitMinutes":17,"carryOverAllowed":false,"carryOverLimitMinutes":null,
                          "expiryRule":"END_OF_VALIDITY","effectiveFrom":null,"effectiveTo":null,
                          "createdAt":"2026-10-21T04:00:00Z","isActive":false}}
                        """, JsonCompareMode.STRICT));
        Map<String, Object> after = versionRow();
        for (String column : List.of("policy_version_id", "policy_id", "version_no", "created_at")) {
            assertThat(after.get(column)).isEqualTo(before.get(column));
        }
        assertThat(groupRepository.findById(1L).orElseThrow().getActivePolicyVersionId()).isEqualTo(11L);
        mvc.perform(get(URL)).andExpect(jsonPath("$.data.unitMinutes").value(17));
        mvc.perform(get("/api/v1/leaves/admin/policies/1/versions"))
                .andExpect(jsonPath("$.data[1].baseType").value("HIRE_DATE"));
    }

    @Test
    void singleFieldUpdatePreservesEveryOmittedField() throws Exception {
        Map<String, Object> before = versionRow();

        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("{\"unitMinutes\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unitMinutes").value(1));

        Map<String, Object> after = versionRow();
        before.remove("unit_minutes");
        after.remove("unit_minutes");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void preservesExplicitStringAndResetsNullableFields() throws Exception {
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"expiryRule":"  CUSTOM  ","carryOverLimitMinutes":null,"effectiveFrom":null,"effectiveTo":null}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiryRule").value("  CUSTOM  "));
        assertThat(versionRow()).containsEntry("carry_over_limit_minutes", null)
                .containsEntry("effective_from", null).containsEntry("effective_to", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"suppliedFields\":[\"unitMinutes\"]}",
            "{\"baseType\":null}", "{\"unitMinutes\":null}", "{\"carryOverAllowed\":null}", "{\"expiryRule\":null}",
            "{\"baseType\":\"INVALID\"}", "{\"baseType\":0}", "{\"baseType\":\"HIRE_DATE\"}",
            "{\"fiscalStart\":null}", "{\"fiscalStart\":\"02-29\"}", "{\"fiscalStart\":\"04-31\"}",
            "{\"fiscalStart\":\"1-01\"}", "{\"unitMinutes\":0}", "{\"unitMinutes\":-1}", "{\"unitMinutes\":1.5}",
            "{\"unitMinutes\":\"30\"}", "{\"carryOverAllowed\":false}", "{\"carryOverAllowed\":\"true\"}",
            "{\"carryOverLimitMinutes\":0}", "{\"carryOverLimitMinutes\":-1}", "{\"expiryRule\":\"\"}",
            "{\"expiryRule\":\"   \"}", "{\"effectiveFrom\":\"2026-10-21\"}", "{\"effectiveFrom\":123}",
            "{\"effectiveTo\":\"2025-12-31T23:59:59Z\"}", "{\"effectiveTo\":\"2026-01-01T09:00:00+09:00\"}"})
    void rejectsInvalidRequestsWithoutAnyDatabaseChanges(String request) throws Exception {
        Map<String, Object> before = versionRow();

        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));

        assertThat(versionRow()).isEqualTo(before);
    }

    @Test
    void rejectsTooLongExpiryAndAllowsColumnLengthBoundary() throws Exception {
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryRule\":\"" + "A".repeat(51) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiryRule\":\"" + "A".repeat(50) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiryRule").value("A".repeat(50)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DRAFT", "CANCELLED", "REJECTED", "GRANT", "RULE_GRANT"})
    void protectsActiveAndUsedVersionsButAcceptsUnchangedValues(String usage) throws Exception {
        addUsage(usage);
        Map<String, Object> before = versionRow();

        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("{\"unitMinutes\":17,\"expiryRule\":\"NEW\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"success":false,"error":{"code":"LEAVE_006",
                         "message":"활성 상태이거나 사용 이력이 있는 휴가 정책 버전은 변경할 수 없습니다."},"data":null}
                        """, JsonCompareMode.STRICT));
        assertThat(versionRow()).isEqualTo(before);

        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"baseType":"FISCAL_YEAR","fiscalStart":"01-01","unitMinutes":60,"carryOverAllowed":true,
                         "carryOverLimitMinutes":240,"expiryRule":"CUSTOM_RULE",
                         "effectiveFrom":"2026-01-01T09:00:00+09:00","effectiveTo":"2027-01-01T09:00:00+09:00"}
                        """))
                .andExpect(status().isOk());
        assertThat(versionRow()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"baseType\":\"HIRE_DATE\",\"fiscalStart\":null}", "{\"fiscalStart\":\"04-01\"}",
            "{\"unitMinutes\":17}", "{\"carryOverAllowed\":false,\"carryOverLimitMinutes\":null}",
            "{\"carryOverLimitMinutes\":null}", "{\"expiryRule\":\"END_OF_VALIDITY\"}",
            "{\"effectiveFrom\":null}", "{\"effectiveTo\":null}"})
    void protectsEveryBusinessSettingOfActiveVersion(String request) throws Exception {
        addUsage("ACTIVE");
        Map<String, Object> before = versionRow();

        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_006"));
        assertThat(versionRow()).isEqualTo(before);
    }

    @Test
    void allowsUnusedVersionWithBothKindsOfChildRulesAndKeepsRulesUnchanged() throws Exception {
        addAccrualRule();
        jdbcTemplate.update("insert into leave_policy_type_rule (rule_id, policy_version_id, leave_type_id) values (1, 13, 1)");
        Map<String, Object> accrualBefore = jdbcTemplate.queryForMap("select * from leave_accrual_rule where rule_id = 1");
        Map<String, Object> typeBefore = jdbcTemplate.queryForMap("select * from leave_policy_type_rule where rule_id = 1");
        assertThat(versionRepository.existsUsageHistory(13L)).isFalse();

        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("{\"unitMinutes\":17}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForMap("select * from leave_accrual_rule where rule_id = 1")).isEqualTo(accrualBefore);
        assertThat(jdbcTemplate.queryForMap("select * from leave_policy_type_rule where rule_id = 1")).isEqualTo(typeBefore);
    }

    @Test
    void mergesDatesAndValidatesFinalPeriodBeforeMutation() throws Exception {
        Map<String, Object> before = versionRow();
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"unitMinutes":17,"effectiveFrom":"2028-01-01T00:00:00Z"}
                        """))
                .andExpect(status().isBadRequest());
        assertThat(versionRow()).isEqualTo(before);

        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"effectiveFrom":"2026-02-01T09:00:00+09:00","effectiveTo":null}
                        """))
                .andExpect(status().isOk());
        assertThat(versionRepository.findById(13L).orElseThrow().getEffectiveFrom().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-02-01T00:00:00Z").toInstant());
    }

    @Test
    void rejectsMissingGroupVersionAndOtherGroupVersion() throws Exception {
        mvc.perform(patch("/api/v1/leaves/admin/policies/9999/versions/13")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unitMinutes\":17}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_004"));
        for (long versionId : List.of(9999L, 21L)) {
            mvc.perform(patch("/api/v1/leaves/admin/policies/1/versions/" + versionId)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"unitMinutes\":17}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_005"));
        }
    }

    @Test
    void deniesNonAdminAndDoesNotModifyVersion() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        Map<String, Object> before = versionRow();

        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("{\"unitMinutes\":17}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        assertThat(versionRow()).isEqualTo(before);
    }

    @Test
    void cannotChangeIdentityVersionNumberCreatedAtOrActivePointerViaRequest() throws Exception {
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"unitMinutes":17,"policyVersionId":21,"policyId":2,"versionNo":999,
                         "createdAt":"2000-01-01T00:00:00Z","isActive":true}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.policyVersionId").value(13))
                .andExpect(jsonPath("$.data.policyId").value(1)).andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-21T04:00:00Z"))
                .andExpect(jsonPath("$.data.isActive").value(false));
        assertThat(groupRepository.findById(1L).orElseThrow().getActivePolicyVersionId()).isNull();
    }

    @Test
    void waitsForGroupLockAndSeesActivationCommittedBeforePatch() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch submitted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> patchResult = transaction.execute(status -> {
                groupRepository.findByIdForUpdate(1L).orElseThrow();
                versionRepository.findByIdAndPolicyIdForUpdate(13L, 1L).orElseThrow();
                Future<MvcResult> result = executor.submit(() -> {
                    submitted.countDown();
                    return mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"unitMinutes\":17}")).andReturn();
                });
                try {
                    assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
                    waitForGroupLockWaiter();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 13 where policy_id = 1");
                return result;
            });

            MvcResult result = patchResult.get(15, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(response.get("error").get("code").asText()).isEqualTo("LEAVE_006");
        }
        assertThat(versionRow()).containsEntry("unit_minutes", 60);
        assertThat(groupRepository.findById(1L).orElseThrow().getActivePolicyVersionId()).isEqualTo(13L);
    }

    @Test
    void concurrentPartialUpdatesPreserveBothChanges() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> performConcurrentPatch("{\"unitMinutes\":17}", ready, start));
            Future<MvcResult> second = executor.submit(() -> performConcurrentPatch("{\"expiryRule\":\"NEW\"}", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(versionRow()).containsEntry("unit_minutes", 17).containsEntry("expiry_rule", "NEW");
    }

    private MvcResult performConcurrentPatch(String request, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 수정 시작 대기 시간 초과");
        }
        return mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(request)).andReturn();
    }

    private void waitForGroupLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            // 동일 트랜잭션에서 통계 스냅샷이 재사용되지 않도록 현재 대기 상태를 다시 읽는다.
            jdbcTemplate.execute("select pg_stat_clear_snapshot()");
            Integer waiters = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and pid <> pg_backend_pid()
                      and wait_event_type = 'Lock' and query like '%leave_policy_group%'
                    """, Integer.class);
            if (waiters != null && waiters > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("그룹 잠금 대기를 PostgreSQL에서 확인하지 못했습니다.");
    }

    private Map<String, Object> versionRow() {
        return jdbcTemplate.queryForMap("select * from leave_policy_version where policy_version_id = 13");
    }

    private void addAccrualRule() {
        jdbcTemplate.update("""
                insert into leave_accrual_rule (rule_id, policy_version_id, frequency, grant_minutes)
                values (1, 13, 'YEARLY', 480)
                """);
    }

    private void addUsage(String usage) {
        if (usage.equals("ACTIVE")) {
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 13 where policy_id = 1");
            return;
        }
        if (usage.equals("GRANT") || usage.equals("RULE_GRANT")) {
            Long ruleId = null;
            if (usage.equals("RULE_GRANT")) {
                addAccrualRule();
                ruleId = 1L;
            }
            jdbcTemplate.update("""
                    insert into leave_grant (account_id, policy_version_id, rule_id, grant_kind, original_minutes, valid_from)
                    values (1, 13, ?, 'AUTO', 480, '2026-01-01T00:00:00Z')
                    """, ruleId);
            return;
        }
        jdbcTemplate.update("""
                insert into leave_request (policy_version_id, leave_type_id, emp_id, status) values (13, 1, 42, ?)
                """, usage);
    }
}
