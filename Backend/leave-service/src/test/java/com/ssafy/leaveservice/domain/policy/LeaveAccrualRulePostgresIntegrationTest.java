package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.policy.entity.AccrualFrequency;
import com.ssafy.leaveservice.domain.policy.entity.LeaveAccrualRule;
import com.ssafy.leaveservice.domain.policy.repository.LeaveAccrualRuleRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=LeaveAccrualRulePostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeaveAccrualRulePostgresIntegrationTest {

    private static final String URL = "/api/v1/leaves/admin/policies/1/versions/13/accrual-rules";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LeaveAccrualRuleRepository ruleRepository;

    @Autowired
    private Flyway flyway;

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
                insert into leave_policy_version (policy_version_id, policy_id, version_no, base_type)
                values (11, 1, 1, 'HIRE_DATE'), (13, 1, 2, 'HIRE_DATE'), (14, 1, 3, 'HIRE_DATE'),
                       (15, 1, 4, 'HIRE_DATE'), (21, 2, 1, 'HIRE_DATE')
                """);
        // 입력 순서·근속 구간·frequency와 무관하게 ID 기준으로 정렬해야 한다.
        jdbcTemplate.update("""
                insert into leave_accrual_rule
                    (rule_id, policy_version_id, min_service_months, max_service_months, frequency, grant_minutes, is_active)
                values (30, 13, 0, 11, 'MONTHLY', 480, true),
                       (10, 13, 12, null, 'YEARLY', 7200, false),
                       (20, 13, 12, 12, 'CUSTOM', 30, true),
                       (50, 15, 0, null, 'YEARLY', 480, true),
                       (60, 21, 0, null, 'YEARLY', 960, true)
                """);
        jdbcTemplate.update("""
                insert into leave_accrual_rule (rule_id, policy_version_id, frequency, grant_minutes)
                values (40, 14, 'ON_HIRE', 60)
                """);
    }

    @Test
    void returnsOnlyVersionRulesWithAllSettingsSortedByRuleId() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"error":null,"data":[
                          {"ruleId":10,"policyVersionId":13,"minServiceMonths":12,"maxServiceMonths":null,
                           "frequency":"YEARLY","grantMinutes":7200,"isActive":false},
                          {"ruleId":20,"policyVersionId":13,"minServiceMonths":12,"maxServiceMonths":12,
                           "frequency":"CUSTOM","grantMinutes":30,"isActive":true},
                          {"ruleId":30,"policyVersionId":13,"minServiceMonths":0,"maxServiceMonths":11,
                           "frequency":"MONTHLY","grantMinutes":480,"isActive":true}
                        ]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsSingleRuleIncludingDatabaseDefaultValues() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/policies/1/versions/14/accrual-rules"))
                .andExpect(status().isOk()).andExpect(content().json("""
                        {"success":true,"error":null,"data":[
                          {"ruleId":40,"policyVersionId":14,"minServiceMonths":0,"maxServiceMonths":null,
                           "frequency":"ON_HIRE","grantMinutes":60,"isActive":true}
                        ]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsEmptyListForVersionWithoutRules() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/policies/1/versions/11/accrual-rules"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}", JsonCompareMode.STRICT));
    }

    @Test
    void returnsGroup404BeforeCheckingVersion() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/policies/9999/versions/13/accrual-rules"))
                .andExpect(status().isNotFound()).andExpect(content().json("""
                        {"success":false,"error":{"code":"LEAVE_004","message":"존재하지 않는 휴가 정책 그룹입니다."},"data":null}
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(longs = {9999L, 21L})
    void rejectsMissingAndOtherGroupVersionsWithIdentical404(Long versionId) throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/policies/1/versions/" + versionId + "/accrual-rules"))
                .andExpect(status().isNotFound()).andExpect(content().json("""
                        {"success":false,"error":{"code":"LEAVE_005","message":"존재하지 않는 휴가 정책 버전입니다."},"data":null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void deniesNonAdminWithExisting403() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        mvc.perform(get(URL)).andExpect(status().isForbidden()).andExpect(content().json("""
                        {"success":false,"error":{"code":"AUTH_009","message":"접근 권한이 없습니다."},"data":null}
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "INACTIVE", "REQUEST", "GRANT"})
    void allowsReadingActiveInactiveAndHistoricallyUsedVersions(String state) throws Exception {
        if (state.equals("ACTIVE")) {
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 13 where policy_id = 1");
        } else if (state.equals("INACTIVE")) {
            jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 11 where policy_id = 1");
        } else if (state.equals("REQUEST")) {
            jdbcTemplate.update("insert into leave_type (leave_type_id, name, balance_mode) values (1, '특별 휴가', 'NONE')");
            jdbcTemplate.update("""
                    insert into leave_request (policy_version_id, leave_type_id, emp_id, status)
                    values (13, 1, 42, 'CANCELLED')
                    """);
        } else {
            jdbcTemplate.update("insert into leave_balance_type (balance_type_id, name) values (1, '연차')");
            jdbcTemplate.update("insert into leave_account (account_id, emp_id, balance_type_id) values (1, 42, 1)");
            jdbcTemplate.update("""
                    insert into leave_grant (account_id, policy_version_id, rule_id, grant_kind, original_minutes, valid_from)
                    values (1, 13, 30, 'AUTO', 480, '2026-01-01T00:00:00Z')
                    """);
        }
        List<Map<String, Object>> before = jdbcTemplate.queryForList("select * from leave_accrual_rule order by rule_id");

        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));

        assertThat(jdbcTemplate.queryForList("select * from leave_accrual_rule order by rule_id")).isEqualTo(before);
    }

    @Test
    void validatesFlywayAndMapsEveryRuleColumnAfterHibernateValidation() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
        LeaveAccrualRule rule = ruleRepository.findById(10L).orElseThrow();

        assertThat(rule.getRuleId()).isEqualTo(10L);
        assertThat(rule.getPolicyVersion().getPolicyVersionId()).isEqualTo(13L);
        assertThat(rule.getMinServiceMonths()).isEqualTo(12);
        assertThat(rule.getMaxServiceMonths()).isNull();
        assertThat(rule.getFrequency()).isEqualTo(AccrualFrequency.YEARLY);
        assertThat(rule.getGrantMinutes()).isEqualTo(7200);
        assertThat(rule.isActive()).isFalse();
    }
}
