package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=LeavePolicyGroupPostgresIntegrationTest",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/",
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeavePolicyGroupPostgresIntegrationTest {

    private static final String URL = "/api/v1/leaves/admin/policies";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbcTemplate.update("delete from leave_request");
        jdbcTemplate.update("delete from leave_grant");
        jdbcTemplate.update("delete from leave_account");
        jdbcTemplate.update("delete from leave_type");
        jdbcTemplate.update("delete from leave_balance_type");
        jdbcTemplate.update("delete from employee_leave_policy_assignment");
        jdbcTemplate.update("delete from leave_policy_version");
        jdbcTemplate.update("delete from leave_policy_group");
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
    }

    @Test
    void returnsAllGroupsInPolicyIdOrderWithOnlyBasicFieldsAndApiResult() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (10, '특별 연차 정책')");
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (3, '일반 직원 연차 정책')");
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (8, '계약 직원 연차 정책')");

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "success": true,
                          "error": null,
                          "data": [
                            {"policyId":3,"name":"일반 직원 연차 정책"},
                            {"policyId":8,"name":"계약 직원 연차 정책"},
                            {"policyId":10,"name":"특별 연차 정책"}
                          ]
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.data[0].policyId").value(3))
                .andExpect(jsonPath("$.data[1].policyId").value(8))
                .andExpect(jsonPath("$.data[2].policyId").value(10));
    }

    @Test
    void returns200AndEmptyDataArray() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}"));
    }

    @Test
    void deniesNonAdminUsingExistingErrorFormat() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        mvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {
                          "success":false,
                          "error":{"code":"AUTH_009","message":"접근 권한이 없습니다."},
                          "data":null
                        }
                        """));
    }

    @Test
    void preservesUnavailableAuthenticationStatus() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));

        mvc.perform(get(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("LEAVE_AUTH_001"));
    }

    @Test
    void returnsExactPolicyGroupWithoutRelatedVersions() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '일반 직원 연차 정책')");
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (2, '다른 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_id, version_no, effective_from, base_type)
                values (1, 1, current_timestamp, 'HIRE_DATE')
                """);

        mvc.perform(get(URL + "/1"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "success": true,
                          "error": null,
                          "data": {"policyId":1,"name":"일반 직원 연차 정책"}
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returns404ForMissingPolicyGroup() throws Exception {
        mvc.perform(get(URL + "/9999"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {
                          "success": false,
                          "error": {"code":"LEAVE_004","message":"존재하지 않는 휴가 정책 그룹입니다."},
                          "data": null
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void deniesNonAdminDetailRequest() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '일반 직원 연차 정책')");
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        mvc.perform(get(URL + "/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }

    @Test
    void preservesUnavailableAuthenticationForDetailRequest() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));

        mvc.perform(get(URL + "/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("LEAVE_AUTH_001"));
    }

    @Test
    void createsGroupWithGeneratedIdAndFindsItInListAndDetail() throws Exception {
        MvcResult createResult = mvc.perform(post(URL).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"일반 직원 연차 정책\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("일반 직원 연차 정책"))
                .andReturn();
        JsonNode createdGroup = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("data");
        long policyId = createdGroup.get("policyId").asLong();
        assertThat(policyId).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "select name from leave_policy_group where policy_id = ?", String.class, policyId
        )).isEqualTo("일반 직원 연차 정책");

        mvc.perform(get(URL + "/" + policyId))
                .andExpect(status().isOk())
                .andExpect(content().json(createResult.getResponse().getContentAsString(), JsonCompareMode.STRICT));
        MvcResult listResult = mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn();
        JsonNode listedGroup = objectMapper.readTree(listResult.getResponse().getContentAsString()).get("data").get(0);
        assertThat(listedGroup).isEqualTo(createdGroup);
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_version", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from employee_leave_policy_assignment", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}"})
    void rejectsMissingNullEmptyAndBlankName(String request) throws Exception {
        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_group", Long.class)).isZero();
    }

    @Test
    void rejectsNameLongerThan100Characters() throws Exception {
        String request = "{\"name\":\"" + "가".repeat(101) + "\"}";

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_group", Long.class)).isZero();
    }

    @Test
    void permits100CharacterName() throws Exception {
        String name = "가".repeat(100);
        String request = "{\"name\":\"" + name + "\"}";

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(name));
    }

    @Test
    void preservesLeadingAndTrailingSpaces() throws Exception {
        String name = "  일반 직원 연차 정책  ";
        String request = "{\"name\":\"" + name + "\"}";

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(name));
        assertThat(jdbcTemplate.queryForObject("select name from leave_policy_group", String.class)).isEqualTo(name);
    }

    @Test
    void permitsDuplicateNamesWithDistinctGeneratedIds() throws Exception {
        String request = "{\"name\":\"중복 가능한 정책\"}";
        MvcResult first = mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn();

        long firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("data").get("policyId").asLong();
        long secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("data").get("policyId").asLong();
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_group", Long.class)).isEqualTo(2L);
    }

    @Test
    void deniesNonAdminCreateRequestWithoutSaving() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content("{\"name\":\"일반 직원 연차 정책\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_group", Long.class)).isZero();
    }

    @Test
    void preservesUnavailableAuthenticationForCreateRequest() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content("{\"name\":\"일반 직원 연차 정책\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("LEAVE_AUTH_001"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_group", Long.class)).isZero();
    }

    @Test
    void updatesNameAndReturnsChangedGroupFromListAndDetail() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '일반 직원 연차 정책')");

        MvcResult updateResult = mvc.perform(patch(URL + "/1").contentType(APPLICATION_JSON)
                        .content("{\"name\":\"정규직 연차 정책\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "success":true,
                          "error":null,
                          "data":{"policyId":1,"name":"정규직 연차 정책"}
                        }
                        """, JsonCompareMode.STRICT))
                .andReturn();

        mvc.perform(get(URL + "/1"))
                .andExpect(status().isOk())
                .andExpect(content().json(updateResult.getResponse().getContentAsString(), JsonCompareMode.STRICT));
        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "success":true,
                          "error":null,
                          "data":[{"policyId":1,"name":"정규직 연차 정책"}]
                        }
                        """, JsonCompareMode.STRICT));
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_group", Long.class)).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}"})
    void rejectsInvalidUpdateNameWithoutChangingExistingName(String request) throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '기존 이름')");

        mvc.perform(patch(URL + "/1").contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(jdbcTemplate.queryForObject("select name from leave_policy_group where policy_id = 1", String.class))
                .isEqualTo("기존 이름");
    }

    @Test
    void rejectsUpdateNameLongerThan100Characters() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '기존 이름')");
        String request = "{\"name\":\"" + "가".repeat(101) + "\"}";

        mvc.perform(patch(URL + "/1").contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void permits100CharacterUpdateName() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '기존 이름')");
        String name = "가".repeat(100);

        mvc.perform(patch(URL + "/1").contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(name));
        assertThat(jdbcTemplate.queryForObject("select name from leave_policy_group where policy_id = 1", String.class))
                .isEqualTo(name);
    }

    @Test
    void preservesSpacesWhenUpdatingName() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '기존 이름')");
        String name = "  정규직 연차 정책  ";

        mvc.perform(patch(URL + "/1").contentType(APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(name));
        assertThat(jdbcTemplate.queryForObject("select name from leave_policy_group where policy_id = 1", String.class))
                .isEqualTo(name);
    }

    @Test
    void permitsSameNameAndDuplicateNameUpdates() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '기존 이름'), (2, '다른 이름')");

        mvc.perform(patch(URL + "/1").contentType(APPLICATION_JSON).content("{\"name\":\"기존 이름\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyId").value(1))
                .andExpect(jsonPath("$.data.name").value("기존 이름"));
        mvc.perform(patch(URL + "/1").contentType(APPLICATION_JSON).content("{\"name\":\"다른 이름\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_group where name = '다른 이름'", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void returnsExisting404ErrorWhenUpdatingMissingGroup() throws Exception {
        mvc.perform(patch(URL + "/9999").contentType(APPLICATION_JSON).content("{\"name\":\"정규직 연차 정책\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {
                          "success":false,
                          "error":{"code":"LEAVE_004","message":"존재하지 않는 휴가 정책 그룹입니다."},
                          "data":null
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void deniesNonAdminUpdateRequestWithoutChangingName() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '기존 이름')");
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        mvc.perform(patch(URL + "/1").contentType(APPLICATION_JSON).content("{\"name\":\"정규직 연차 정책\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
        assertThat(jdbcTemplate.queryForObject("select name from leave_policy_group where policy_id = 1", String.class))
                .isEqualTo("기존 이름");
    }

    @Test
    void preservesUnavailableAuthenticationForUpdateRequest() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));

        mvc.perform(patch(URL + "/1").contentType(APPLICATION_JSON).content("{\"name\":\"정규직 연차 정책\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("LEAVE_AUTH_001"));
    }

    @Test
    void permitsRenameWithVersionsAndAssignmentsAndPreservesExistingHistory() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '기존 이름')");
        Long policyVersionId = jdbcTemplate.queryForObject("""
                insert into leave_policy_version (policy_id, version_no, effective_from, base_type, unit_minutes)
                values (1, 1, current_timestamp, 'HIRE_DATE', 60) returning policy_version_id
                """, Long.class);
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment (emp_id, policy_id, effective_from)
                values (42, 1, current_timestamp)
                """);
        Long balanceTypeId = jdbcTemplate.queryForObject(
                "insert into leave_balance_type (name) values ('연차') returning balance_type_id", Long.class
        );
        Long leaveTypeId = jdbcTemplate.queryForObject("""
                insert into leave_type (name, balance_type_id) values ('연차', ?) returning leave_type_id
                """, Long.class, balanceTypeId);
        Long accountId = jdbcTemplate.queryForObject("""
                insert into leave_account (emp_id, balance_type_id) values (42, ?) returning account_id
                """, Long.class, balanceTypeId);
        jdbcTemplate.update("""
                insert into leave_grant (account_id, policy_version_id, grant_kind, original_minutes, valid_from)
                values (?, ?, 'MANUAL', 480, current_timestamp)
                """, accountId, policyVersionId);
        jdbcTemplate.update("""
                insert into leave_request (policy_version_id, leave_type_id, emp_id, status)
                values (?, ?, 42, 'APPROVED')
                """, policyVersionId, leaveTypeId);

        // 관련 행 전체를 비교해 FK뿐 아니라 설정·기간·신청 상태·발생량도 유지되는지 확인한다.
        List<String> relatedTables = List.of(
                "leave_policy_version", "employee_leave_policy_assignment", "leave_grant", "leave_request"
        );
        Map<String, List<Map<String, Object>>> relatedRowsBeforeUpdate = new LinkedHashMap<>();
        for (String table : relatedTables) {
            relatedRowsBeforeUpdate.put(table, jdbcTemplate.queryForList("select * from " + table));
        }

        mvc.perform(patch(URL + "/1").contentType(APPLICATION_JSON).content("{\"name\":\"정규직 연차 정책\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyId").value(1))
                .andExpect(jsonPath("$.data.name").value("정규직 연차 정책"));

        for (String table : relatedTables) {
            assertThat(jdbcTemplate.queryForList("select * from " + table)).isEqualTo(relatedRowsBeforeUpdate.get(table));
        }
    }
}
