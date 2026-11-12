package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyTypeRule;
import com.ssafy.leaveservice.domain.policy.entity.PolicyAllowedUnits;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyTypeRuleRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=LeavePolicyTypeRulePostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeavePolicyTypeRulePostgresIntegrationTest {

    private static final String URL = "/api/v1/leaves/admin/policies/1/versions/14/type-rules";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LeavePolicyTypeRuleRepository ruleRepository;

    @Autowired
    private Flyway flyway;

    @MockitoBean
    private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = null");
        for (String table : List.of("leave_request", "leave_grant", "leave_policy_type_rule", "leave_account",
                "leave_type", "leave_balance_type", "leave_policy_version", "leave_policy_group")) {
            jdbcTemplate.update("delete from " + table);
        }
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '일반 정책'), (2, '다른 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id, policy_id, version_no, base_type)
                values (11,1,1,'HIRE_DATE'),(14,1,2,'HIRE_DATE'),(15,1,3,'HIRE_DATE'),(21,2,1,'HIRE_DATE')
                """);
        jdbcTemplate.update("""
                insert into leave_type (leave_type_id, name, balance_mode, is_active)
                values (1,'유형 1','NONE',true),(2,'비활성 유형','NONE',false),(3,'연결 없는 유형','NONE',true)
                """);
        // ruleId 순서와 반대가 되도록 구성하여 leaveTypeId 정렬을 검증한다.
        jdbcTemplate.update("""
                insert into leave_policy_type_rule (rule_id,policy_version_id,leave_type_id,is_requestable,allowed_units,min_minutes,max_minutes)
                values (80,14,2,false,'HOUR',15,120),(40,15,1,true,'HALF',30,60),(30,21,1,true,'MIXED',null,480)
                """);
        jdbcTemplate.update("insert into leave_policy_type_rule (rule_id,policy_version_id,leave_type_id) values (90,14,1)");
    }

    @Test
    void returnsAllRuleColumnsInTypeIdOrderIncludingInactiveTypesAndNonRequestableRules() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(content().json("""
                        {"success":true,"error":null,"data":[
                          {"ruleId":90,"policyVersionId":14,"leaveTypeId":1,"isRequestable":true,
                           "allowedUnits":"FULL","minMinutes":null,"maxMinutes":null},
                          {"ruleId":80,"policyVersionId":14,"leaveTypeId":2,"isRequestable":false,
                           "allowedUnits":"HOUR","minMinutes":15,"maxMinutes":120}
                        ]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsOneRuleForSameTypeInAnotherVersionWithoutMixingSettings() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/policies/1/versions/15/type-rules"))
                .andExpect(status().isOk()).andExpect(content().json("""
                        {"success":true,"error":null,"data":[
                          {"ruleId":40,"policyVersionId":15,"leaveTypeId":1,"isRequestable":true,
                           "allowedUnits":"HALF","minMinutes":30,"maxMinutes":60}
                        ]}
                        """, JsonCompareMode.STRICT));
        mvc.perform(get("/api/v1/leaves/admin/policies/2/versions/21/type-rules"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].allowedUnits").value("MIXED"))
                .andExpect(jsonPath("$.data[0].maxMinutes").value(480));
    }

    @Test
    void returnsEmptyListWithoutInventingRulesFromTypeMaster() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/policies/1/versions/11/type-rules"))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}", JsonCompareMode.STRICT));
    }

    @Test
    void changingMasterActivationDoesNotChangeRuleResponse() throws Exception {
        MvcResult before = mvc.perform(get(URL)).andExpect(status().isOk()).andReturn();
        jdbcTemplate.update("update leave_type set is_active = false where leave_type_id = 1");

        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(content().json(before.getResponse().getContentAsString(), JsonCompareMode.STRICT));
    }

    @Test
    void returnsGroup404BeforeVersionQuery() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/policies/9999/versions/14/type-rules"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_004"));
    }

    @ParameterizedTest
    @ValueSource(longs = {9999L,21L})
    void returnsSame404ForMissingAndForeignVersion(Long versionId) throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/policies/1/versions/" + versionId + "/type-rules"))
                .andExpect(status().isNotFound()).andExpect(content().json("""
                        {"success":false,"error":{"code":"LEAVE_005","message":"존재하지 않는 휴가 정책 버전입니다."},"data":null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void rejectsNonAdminWithExisting403() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        mvc.perform(get(URL)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE","INACTIVE","REQUEST","GRANT"})
    void allowsEveryVersionStateAndDoesNotChangeStoredRows(String state) throws Exception {
        if (state.equals("ACTIVE")) {
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 14 where policy_id = 1");
        } else if (state.equals("INACTIVE")) {
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 11 where policy_id = 1");
        } else if (state.equals("REQUEST")) {
            jdbcTemplate.update("insert into leave_request (policy_version_id,leave_type_id,emp_id,status) values (14,1,42,'CANCELLED')");
        } else {
            jdbcTemplate.update("insert into leave_balance_type (balance_type_id,name) values (1,'연차')");
            jdbcTemplate.update("insert into leave_account (account_id,emp_id,balance_type_id) values (1,42,1)");
            jdbcTemplate.update("""
                    insert into leave_grant (account_id,policy_version_id,grant_kind,original_minutes,valid_from)
                    values (1,14,'MANUAL',480,'2026-01-01T00:00:00Z')
                    """);
        }
        Map<String, List<Map<String, Object>>> before = storedRows();

        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));

        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void mapsEveryColumnAfterFlywayAndHibernateValidation() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
        LeavePolicyTypeRule rule = ruleRepository.findById(90L).orElseThrow();

        assertThat(rule.getRuleId()).isEqualTo(90L);
        assertThat(rule.getPolicyVersionId()).isEqualTo(14L);
        assertThat(rule.getLeaveTypeId()).isEqualTo(1L);
        assertThat(rule.isRequestable()).isTrue();
        assertThat(rule.getAllowedUnits()).isEqualTo(PolicyAllowedUnits.FULL);
        assertThat(rule.getMinMinutes()).isNull();
        assertThat(rule.getMaxMinutes()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "insert into leave_policy_type_rule (policy_version_id,leave_type_id) values (14,1)",
            "insert into leave_policy_type_rule (policy_version_id,leave_type_id) values (9999,3)",
            "insert into leave_policy_type_rule (policy_version_id,leave_type_id) values (11,9999)",
            "insert into leave_policy_type_rule (policy_version_id,leave_type_id,allowed_units) values (11,3,'DAY')",
            "insert into leave_policy_type_rule (policy_version_id,leave_type_id,min_minutes) values (11,3,0)",
            "insert into leave_policy_type_rule (policy_version_id,leave_type_id,min_minutes,max_minutes) values (11,3,60,30)"
    })
    void databaseStillEnforcesUniqueForeignKeysAndChecks(String invalidSql) {
        assertThatThrownBy(() -> jdbcTemplate.update(invalidSql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("leave_policy_group", "leave_policy_version", "leave_type",
                "leave_policy_type_rule", "leave_request", "leave_grant")) {
            rows.put(table, jdbcTemplate.queryForList("select *, xmin::text as row_transaction_id from " + table + " order by 1"));
        }
        return rows;
    }
}
