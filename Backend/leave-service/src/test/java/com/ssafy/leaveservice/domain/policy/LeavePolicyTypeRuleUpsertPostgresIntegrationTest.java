package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyTypeRuleUpsertRequest;
import com.ssafy.leaveservice.domain.policy.entity.PolicyAllowedUnits;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyTypeRuleService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
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
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        "leave.test.database-owner=LeavePolicyTypeRuleUpsertPostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeavePolicyTypeRuleUpsertPostgresIntegrationTest {

    private static final String VERSION_URL = "/api/v1/leaves/admin/policies/1/versions/15";
    private static final String URL = VERSION_URL + "/type-rules/";
    private static final String BODY = "{\"isRequestable\":true,\"allowedUnits\":\"HALF\",\"minMinutes\":30,\"maxMinutes\":60}";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LeavePolicyTypeRuleService service;

    @Autowired
    private LeavePolicyGroupRepository groupRepository;

    @Autowired
    private LeaveTypeRepository leaveTypeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = null");
        for (String table : List.of("leave_ledger", "leave_request", "leave_grant", "leave_accrual_rule", "leave_policy_type_rule",
                "leave_account", "leave_type", "leave_balance_type", "leave_policy_version", "leave_policy_group")) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        jdbcTemplate.update("insert into leave_policy_group (policy_id,name) values (1,'일반 정책'),(2,'다른 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id,policy_id,version_no,base_type)
                values (11,1,1,'HIRE_DATE'),(15,1,2,'HIRE_DATE'),(16,1,3,'HIRE_DATE'),(21,2,1,'HIRE_DATE')
                """);
        jdbcTemplate.update("""
                insert into leave_type (leave_type_id,name,balance_mode,request_unit,max_minutes,is_active)
                values (1,'일 단위','NONE','DAY',120,true),(2,'비활성 반일','NONE','HALF_DAY',120,false),
                       (3,'시간 단위','NONE','HOUR',90,true),(4,'상한 없는 혼합','NONE','MIXED',null,true),
                       (5,'혼합','NONE','MIXED',120,true),(6,'일 단위 신규','NONE','DAY',null,true),
                       (7,'동시성 검증용','NONE','MIXED',120,true)
                """);
        jdbcTemplate.update("""
                insert into leave_policy_type_rule (rule_id,policy_version_id,leave_type_id,is_requestable,allowed_units,min_minutes,max_minutes)
                values (40,15,2,true,'HALF',30,60),(41,15,1,true,'FULL',null,90),
                       (50,16,2,true,'HALF',30,60),(60,21,2,true,'HALF',null,null)
                """);
        jdbcTemplate.queryForObject("select setval(pg_get_serial_sequence('leave_policy_type_rule','rule_id'),100,false)", Long.class);
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id,name) values (1,'연차')");
        jdbcTemplate.update("insert into leave_account (account_id,emp_id,balance_type_id) values (1,42,1)");
    }

    @Test
    void insertsAndRetriesWithoutChangingIdOrPhysicalRow() throws Exception {
        String body = "{\"isRequestable\":true,\"allowedUnits\":\"HOUR\",\"minMinutes\":17,\"maxMinutes\":30}";
        MvcResult first = mvc.perform(put(URL + "3").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().json("""
                        {"success":true,"error":null,"data":{"ruleId":100,"policyVersionId":15,"leaveTypeId":3,
                         "isRequestable":true,"allowedUnits":"HOUR","minMinutes":17,"maxMinutes":30}}
                        """, JsonCompareMode.STRICT)).andReturn();
        Map<String, List<Map<String, Object>>> before = storedRows();

        mvc.perform(put(URL + "3").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().json(first.getResponse().getContentAsString(), JsonCompareMode.STRICT));
        assertThat(storedRows()).isEqualTo(before);
        mvc.perform(get(VERSION_URL + "/type-rules")).andExpect(jsonPath("$.data[2].ruleId").value(100));
    }

    @Test
    void updatesInactiveTypeRuleInPlaceAndClearsOmittedNullableFields() throws Exception {
        List<Map<String, Object>> masters = jdbcTemplate.queryForList("select *, xmin::text as row_version from leave_type order by 1");
        List<Map<String, Object>> otherRules = jdbcTemplate.queryForList("select * from leave_policy_type_rule where rule_id <> 40 order by 1");

        mvc.perform(put(URL + "2").contentType(MediaType.APPLICATION_JSON).content("""
                        {"isRequestable":false,"allowedUnits":"HALF","minMinutes":30,"maxMinutes":90}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ruleId").value(40))
                .andExpect(jsonPath("$.data.isRequestable").value(false)).andExpect(jsonPath("$.data.maxMinutes").value(90));
        mvc.perform(get(VERSION_URL + "/type-rules")).andExpect(jsonPath("$.data[1].maxMinutes").value(90));
        mvc.perform(put(URL + "2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isRequestable\":false,\"allowedUnits\":\"HALF\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ruleId").value(40));

        Map<String, Object> rule = jdbcTemplate.queryForMap("select * from leave_policy_type_rule where rule_id = 40");
        assertThat(rule).containsEntry("min_minutes", null).containsEntry("max_minutes", null);
        assertThat(jdbcTemplate.queryForList("select *, xmin::text as row_version from leave_type order by 1")).isEqualTo(masters);
        assertThat(jdbcTemplate.queryForList("select * from leave_policy_type_rule where rule_id <> 40 order by 1")).isEqualTo(otherRules);
    }

    static Stream<Arguments> unitCompatibility() {
        Map<Long, PolicyAllowedUnits> singleUnits = Map.of(1L, PolicyAllowedUnits.FULL, 2L, PolicyAllowedUnits.HALF, 3L, PolicyAllowedUnits.HOUR);
        List<Arguments> arguments = new ArrayList<>();
        for (long typeId : List.of(1L, 2L, 3L, 4L)) {
            for (PolicyAllowedUnits units : PolicyAllowedUnits.values()) {
                boolean compatible = typeId == 4L || singleUnits.get(typeId) == units;
                arguments.add(Arguments.of(typeId, units, compatible));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("unitCompatibility")
    void enforcesAllSixteenUnitCombinations(long typeId, PolicyAllowedUnits units, boolean compatible) throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        MvcResult result = mvc.perform(put(URL + typeId).contentType(MediaType.APPLICATION_JSON).content(
                "{\"isRequestable\":true,\"allowedUnits\":\"" + units + "\"}"
        )).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(compatible ? 200 : 400);
        if (!compatible) {
            assertThat(storedRows()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"isRequestable\":true,\"allowedUnits\":\"FULL\",\"minMinutes\":121}",
            "{\"isRequestable\":true,\"allowedUnits\":\"FULL\",\"maxMinutes\":121}"})
    void rejectsEitherExplicitLimitAboveMasterCap(String body) throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();

        mvc.perform(put(URL + "5").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void allowsCapBoundaryAndNullWithoutRemovingMasterCap() throws Exception {
        mvc.perform(put(URL + "5").contentType(MediaType.APPLICATION_JSON).content("""
                        {"isRequestable":true,"allowedUnits":"MIXED","minMinutes":120,"maxMinutes":120}
                        """))
                .andExpect(status().isOk());
        mvc.perform(put(URL + "5").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isRequestable\":true,\"allowedUnits\":\"MIXED\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("select max_minutes from leave_type where leave_type_id = 5", Integer.class)).isEqualTo(120);
        mvc.perform(put(URL + "4").contentType(MediaType.APPLICATION_JSON).content("""
                        {"isRequestable":true,"allowedUnits":"FULL","minMinutes":9000,"maxMinutes":9001}
                        """))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "{\"allowedUnits\":\"HALF\"}",
            "{\"isRequestable\":null,\"allowedUnits\":\"HALF\"}", "{\"isRequestable\":true}",
            "{\"isRequestable\":true,\"allowedUnits\":null}", "{\"isRequestable\":true,\"allowedUnits\":\"INVALID\"}",
            "{\"isRequestable\":true,\"allowedUnits\":0}", "{\"isRequestable\":\"true\",\"allowedUnits\":\"HALF\"}",
            "{\"isRequestable\":true,\"allowedUnits\":\"HALF\",\"minMinutes\":0}",
            "{\"isRequestable\":true,\"allowedUnits\":\"HALF\",\"minMinutes\":-1}",
            "{\"isRequestable\":true,\"allowedUnits\":\"HALF\",\"maxMinutes\":0}",
            "{\"isRequestable\":true,\"allowedUnits\":\"HALF\",\"maxMinutes\":-1}",
            "{\"isRequestable\":true,\"allowedUnits\":\"HALF\",\"minMinutes\":61,\"maxMinutes\":60}",
            "{\"isRequestable\":true,\"allowedUnits\":\"HALF\",\"minMinutes\":1.5}",
            "{\"isRequestable\":true,\"allowedUnits\":\"HALF\",\"maxMinutes\":\"60\"}"})
    void rejectsInvalidRequestsWithoutDatabaseChanges(String body) throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();

        mvc.perform(put(URL + "2").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void usesPathIdentityAndNeverMovesExistingRule() throws Exception {
        mvc.perform(put(URL + "2").contentType(MediaType.APPLICATION_JSON).content("""
                        {"ruleId":999,"policyVersionId":21,"leaveTypeId":6,"isRequestable":false,"allowedUnits":"HALF"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ruleId").value(40))
                .andExpect(jsonPath("$.data.policyVersionId").value(15)).andExpect(jsonPath("$.data.leaveTypeId").value(2));
    }

    @Test
    void returnsExistingAuthenticationAndNotFoundErrorsInOrder() throws Exception {
        mvc.perform(put("/api/v1/leaves/admin/policies/9999/versions/15/type-rules/2").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_004"));
        for (long versionId : List.of(9999L, 21L)) {
            mvc.perform(put("/api/v1/leaves/admin/policies/1/versions/" + versionId + "/type-rules/2")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_005"));
        }
        mvc.perform(put(URL + "9999").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_002"));
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        mvc.perform(put(URL + "2").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DRAFT", "CANCELLED", "REJECTED", "GRANT", "RULE_GRANT", "EXPIRED_CONSUMED"})
    void protectsCreationAndChangesButAllowsUnchangedRequests(String usage) throws Exception {
        addUsage(usage);
        Map<String, List<Map<String, Object>>> before = storedRows();

        mvc.perform(put(URL + "2").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("60", "90")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_006"));
        mvc.perform(put(URL + "5").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_006"));
        mvc.perform(put(URL + "2").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ruleId").value(40));
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void ruleConfigurationAloneDoesNotCountAsUsage() throws Exception {
        addAccrualRule();
        mvc.perform(put(URL + "5").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(longs = {2L, 5L})
    void databaseFailureRollsBackUpdateOrInsert(long typeId) throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        jdbcTemplate.execute("alter table leave_policy_type_rule add constraint put_test_check check (max_minutes is null or max_minutes <> 77)");
        try {
            mvc.perform(put(URL + typeId).contentType(MediaType.APPLICATION_JSON).content(BODY.replace("60", "77")))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
            assertThat(storedRows()).isEqualTo(before);
        } finally {
            jdbcTemplate.execute("alter table leave_policy_type_rule drop constraint put_test_check");
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {2L, 7L})
    void concurrentPutsKeepExactlyOneRuleAndTheSameId(long typeId) throws Exception {
        String body = typeId == 2L ? BODY : BODY.replace("HALF", "FULL");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> concurrentPut(typeId, body, ready, start));
            Future<MvcResult> second = executor.submit(() -> concurrentPut(typeId, body, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult firstResult = first.get(15, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(15, TimeUnit.SECONDS);
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(objectMapper.readTree(firstResult.getResponse().getContentAsString()))
                    .isEqualTo(objectMapper.readTree(secondResult.getResponse().getContentAsString()));
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_type_rule where policy_version_id=15 and leave_type_id=?", Integer.class, typeId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE", "ACCRUAL_PUT"})
    void serializesWithExistingVersionAndAccrualRuleMutationPaths(String action) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> result = transaction.execute(status -> {
                groupRepository.findByIdForUpdate(1L).orElseThrow();
                performMutation(action);
                Future<MvcResult> pending = executor.submit(() -> mvc.perform(put(URL + "2")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn());
                waitForLock("%leave_policy_group%");
                return pending;
            });
            assertThat(result.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(action.equals("DELETE") ? 404 : 200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FULL", "HALF"})
    void masterPatchFirstMakesPutValidateNewUnitAndCapAfterWaiting(String allowedUnits) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> result = transaction.execute(status -> {
                leaveTypeRepository.findByIdForUpdate(7L).orElseThrow();
                performMasterPatch("{\"requestUnit\":\"DAY\",\"maxMinutes\":60}");
                int requestedMaxMinutes = allowedUnits.equals("FULL") ? 90 : 30;
                String body = "{\"isRequestable\":true,\"allowedUnits\":\"" + allowedUnits
                        + "\",\"maxMinutes\":" + requestedMaxMinutes + "}";
                Future<MvcResult> pending = executor.submit(() -> mvc.perform(put(URL + "7").contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andReturn());
                waitForLock("%leave_type %");
                return pending;
            });
            assertThat(result.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(jdbcTemplate.queryForObject("select max_minutes from leave_type where leave_type_id=7", Integer.class)).isEqualTo(60);
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_type_rule where leave_type_id=7", Integer.class)).isZero();
    }

    @Test
    void rulePutFirstMakesMasterPatchObserveReferenceAndRejectProtectedChange() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<MvcResult> patchResult = transaction.execute(status -> {
                service.upsertTypeRule(1L, 15L, 7L, new LeavePolicyTypeRuleUpsertRequest(true, PolicyAllowedUnits.FULL, 30, 60));
                Future<MvcResult> pending = executor.submit(() -> mvc.perform(patch("/api/v1/leaves/admin/types/7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxMinutes\":90}")).andReturn());
                waitForLock("%leave_type %");
                return pending;
            });
            MvcResult result = patchResult.get(15, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("error").get("code").asText()).isEqualTo("LEAVE_003");
        }
        assertThat(jdbcTemplate.queryForObject("select max_minutes from leave_type where leave_type_id=7", Integer.class)).isEqualTo(120);
    }

    private MvcResult concurrentPut(long typeId, String body, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 요청 시작 대기 시간 초과");
        }
        return mvc.perform(put(URL + typeId).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private void performMutation(String action) {
        MockHttpServletRequestBuilder mutation;
        if (action.equals("DELETE")) {
            mutation = delete(VERSION_URL);
        } else if (action.equals("PATCH")) {
            mutation = patch(VERSION_URL).contentType(MediaType.APPLICATION_JSON).content("{\"unitMinutes\":17}");
        } else {
            mutation = put(VERSION_URL + "/accrual-rules").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"rules\":[{\"minServiceMonths\":0,\"frequency\":\"YEARLY\",\"grantMinutes\":480,\"isActive\":true}]}");
        }
        try {
            assertThat(mvc.perform(mutation).andReturn().getResponse().getStatus()).isEqualTo(200);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void performMasterPatch(String body) {
        try {
            assertThat(mvc.perform(patch("/api/v1/leaves/admin/types/7").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("leave_policy_group", "leave_policy_version", "leave_type", "leave_policy_type_rule",
                "leave_request", "leave_grant", "leave_accrual_rule", "leave_ledger")) {
            rows.put(table, jdbcTemplate.queryForList("select *, xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }

    private void addAccrualRule() {
        jdbcTemplate.update("insert into leave_accrual_rule (rule_id,policy_version_id,frequency,grant_minutes) values (15,15,'YEARLY',480)");
    }

    private void addUsage(String usage) {
        if (usage.equals("ACTIVE")) {
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id=15 where policy_id=1");
        } else if (usage.equals("GRANT") || usage.equals("RULE_GRANT") || usage.equals("EXPIRED_CONSUMED")) {
            Long ruleId = null;
            if (usage.equals("RULE_GRANT")) {
                addAccrualRule();
                ruleId = 15L;
            }
            jdbcTemplate.update("""
                    insert into leave_grant (grant_id,account_id,policy_version_id,rule_id,grant_kind,original_minutes,valid_from,expires_on)
                    values (15,1,15,?,'AUTO',480,'2020-01-01T00:00:00Z','2021-01-01T00:00:00Z')
                    """, ruleId);
            if (usage.equals("EXPIRED_CONSUMED")) {
                jdbcTemplate.update("""
                        insert into leave_ledger (account_id,grant_id,event_type,quantity_delta,idempotency_key)
                        values (1,15,'GRANT',480,'grant-15'),(1,15,'CONSUME',-480,'consume-15')
                        """);
            }
        } else {
            jdbcTemplate.update("insert into leave_request (policy_version_id,leave_type_id,emp_id,status) values (15,2,42,?)", usage);
        }
    }
}
