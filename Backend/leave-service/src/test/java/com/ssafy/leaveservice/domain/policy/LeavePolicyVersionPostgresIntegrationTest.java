package com.ssafy.leaveservice.domain.policy;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionCreateRequest;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionDetailResponse;
import com.ssafy.leaveservice.domain.policy.service.LeavePolicyVersionService;
import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=LeavePolicyVersionPostgresIntegrationTest",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/",
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeavePolicyVersionPostgresIntegrationTest {

    private static final String URL = "/api/v1/leaves/admin/policies/1/versions";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LeavePolicyVersionRepository leavePolicyVersionRepository;

    @Autowired
    private Flyway flyway;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LeavePolicyVersionService leavePolicyVersionService;

    @MockitoBean
    private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = null");
        jdbcTemplate.update("delete from leave_policy_version");
        jdbcTemplate.update("delete from leave_policy_group");
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (1, '일반 정책'), (2, '다른 정책')");
        // 입력 순서·ID·시행 기간과 무관하게 버전 번호 및 활성 포인터를 사용해야 한다.
        jdbcTemplate.update("""
                insert into leave_policy_version
                    (policy_version_id, policy_id, version_no, base_type, effective_from, effective_to)
                values (12, 1, 3, 'HIRE_DATE', '2099-01-01T00:00:00Z', '2100-01-01T00:00:00Z'),
                       (11, 1, 1, 'HIRE_DATE', '2000-01-01T00:00:00Z', '2001-01-01T00:00:00Z'),
                       (21, 2, 1, 'HIRE_DATE', null, null)
                """);
        jdbcTemplate.update("""
                insert into leave_policy_version
                    (policy_version_id, policy_id, version_no, base_type, fiscal_start,
                     unit_minutes, carry_over_allowed, carry_over_limit_minutes, expiry_rule)
                values (13, 1, 2, 'FISCAL_YEAR', '01-01', 60, true, 240, 'END_OF_VALIDITY')
                """);
        jdbcTemplate.queryForObject(
                "select setval(pg_get_serial_sequence('leave_policy_version', 'policy_version_id'), 100, false)", Long.class
        );
    }

    @Test
    void returnsOnlyGroupVersionsInVersionNumberOrderAndUsesPointerRatherThanDates() throws Exception {
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 11 where policy_id = 1");

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "success": true,
                          "error": null,
                          "data": [
                            {"policyVersionId":11,"versionNo":1,"baseType":"HIRE_DATE",
                             "effectiveFrom":"2000-01-01T00:00:00Z","effectiveTo":"2001-01-01T00:00:00Z","isActive":true},
                            {"policyVersionId":13,"versionNo":2,"baseType":"FISCAL_YEAR",
                             "effectiveFrom":null,"effectiveTo":null,"isActive":false},
                            {"policyVersionId":12,"versionNo":3,"baseType":"HIRE_DATE",
                             "effectiveFrom":"2099-01-01T00:00:00Z","effectiveTo":"2100-01-01T00:00:00Z","isActive":false}
                          ]
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsAllInactiveWhenPointerIsNull() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].isActive").value(false))
                .andExpect(jsonPath("$.data[1].isActive").value(false))
                .andExpect(jsonPath("$.data[2].isActive").value(false));
    }

    @Test
    void reflectsPointerChangesOnSubsequentRequests() throws Exception {
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 11 where policy_id = 1");
        mvc.perform(get(URL)).andExpect(jsonPath("$.data[0].isActive").value(true));

        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 13 where policy_id = 1");
        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].isActive").value(false))
                .andExpect(jsonPath("$.data[1].isActive").value(true))
                .andExpect(jsonPath("$.data[2].isActive").value(false));
    }

    @Test
    void returnsEmptyListForExistingGroupWithoutVersions() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (3, '버전 없는 정책')");

        mvc.perform(get("/api/v1/leaves/admin/policies/3/versions"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}", JsonCompareMode.STRICT));
    }

    @Test
    void returnsExisting404ErrorForMissingGroup() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/policies/9999/versions"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"success":false,
                         "error":{"code":"LEAVE_004","message":"존재하지 않는 휴가 정책 그룹입니다."},"data":null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void deniesNonAdminWithExisting403Response() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        mvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }

    @Test
    void preservesUnavailableAuthenticationResponse() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));

        mvc.perform(get(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("LEAVE_AUTH_001"));
    }

    @Test
    void mapsVersionSettingsAfterV4AndValidatesFlyway() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");

        LeavePolicyVersion version = leavePolicyVersionRepository.findById(13L).orElseThrow();

        assertThat(version.getPolicyVersionId()).isEqualTo(13L);
        assertThat(version.getVersionNo()).isEqualTo(2);
        assertThat(version.getBaseType()).isEqualTo(PolicyBaseType.FISCAL_YEAR);
        assertThat(version.getEffectiveFrom()).isNull();
        assertThat(version.getEffectiveTo()).isNull();
        assertThat(version.getFiscalStart()).isEqualTo("01-01");
        assertThat(version.getUnitMinutes()).isEqualTo(60);
        assertThat(version.isCarryOverAllowed()).isTrue();
        assertThat(version.getCarryOverLimitMinutes()).isEqualTo(240);
        assertThat(version.getExpiryRule()).isEqualTo("END_OF_VALIDITY");
        assertThat(version.getCreatedAt()).isNotNull();
    }

    @Test
    void preservesGroupResponseAndActivePointerWhenRenamingGroup() throws Exception {
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 13 where policy_id = 1");

        mvc.perform(patch("/api/v1/leaves/admin/policies/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 이름\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"error":null,"data":{"policyId":1,"name":"새 이름"}}
                        """, JsonCompareMode.STRICT));
        assertThat(jdbcTemplate.queryForObject(
                "select active_policy_version_id from leave_policy_group where policy_id = 1", Long.class
        )).isEqualTo(13L);
        mvc.perform(get(URL)).andExpect(jsonPath("$.data[1].isActive").value(true));
    }

    @Test
    void returnsStrictDetailWithAllSettingsAndNoChildCollections() throws Exception {
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 13 where policy_id = 1");
        jdbcTemplate.update("update leave_policy_version set created_at = '2026-10-21T04:00:00Z' where policy_version_id = 13");

        mvc.perform(get(URL + "/13"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"error":null,"data":{
                          "policyVersionId":13,"policyId":1,"versionNo":2,"baseType":"FISCAL_YEAR",
                          "fiscalStart":"01-01","unitMinutes":60,"carryOverAllowed":true,
                          "carryOverLimitMinutes":240,"expiryRule":"END_OF_VALIDITY",
                          "effectiveFrom":null,"effectiveTo":null,"createdAt":"2026-10-21T04:00:00Z",
                          "isActive":true
                        }}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsNullableSettingsAndInactiveDetailWithoutPointer() throws Exception {
        mvc.perform(get(URL + "/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyVersionId").value(11))
                .andExpect(jsonPath("$.data.policyId").value(1))
                .andExpect(jsonPath("$.data.fiscalStart").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.carryOverLimitMinutes").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.unitMinutes").value(30))
                .andExpect(jsonPath("$.data.carryOverAllowed").value(false))
                .andExpect(jsonPath("$.data.isActive").value(false));
    }

    @Test
    void detailUsesPointerInsteadOfDatesOrHighestVersionNumber() throws Exception {
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 11 where policy_id = 1");

        mvc.perform(get(URL + "/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(true));
        mvc.perform(get(URL + "/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));

        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 12 where policy_id = 1");
        mvc.perform(get(URL + "/11")).andExpect(jsonPath("$.data.isActive").value(false));
        mvc.perform(get(URL + "/12")).andExpect(jsonPath("$.data.isActive").value(true));
    }

    @ParameterizedTest
    @ValueSource(longs = {9999L, 21L})
    void returnsSameVersion404ForMissingAndOtherGroupVersion(Long versionId) throws Exception {
        mvc.perform(get(URL + "/" + versionId))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"success":false,
                         "error":{"code":"LEAVE_005","message":"존재하지 않는 휴가 정책 버전입니다."},"data":null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void detailReturnsGroup404BeforeCheckingVersion() throws Exception {
        mvc.perform(get("/api/v1/leaves/admin/policies/9999/versions/11"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LEAVE_004"));
    }

    @Test
    void deniesNonAdminDetail() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        mvc.perform(get(URL + "/13"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }

    @Test
    void preservesUnavailableAuthenticationForDetail() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));

        mvc.perform(get(URL + "/13"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("LEAVE_AUTH_001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-21T13:00:00+09:00", "2026-10-21T04:00:00Z"})
    void preservesTimestampInstantsAcrossOffsetsAndSerializesIso8601(String timestamp) throws Exception {
        OffsetDateTime effectiveFrom = OffsetDateTime.parse(timestamp);
        OffsetDateTime effectiveTo = effectiveFrom.plusDays(1);
        OffsetDateTime createdAt = effectiveFrom.minusDays(1);
        jdbcTemplate.update("""
                update leave_policy_version set effective_from = ?, effective_to = ?, created_at = ?
                where policy_version_id = 11
                """, effectiveFrom, effectiveTo, createdAt);

        LeavePolicyVersion policyVersion = leavePolicyVersionRepository.findById(11L).orElseThrow();
        assertThat(policyVersion.getEffectiveFrom().toInstant()).isEqualTo(effectiveFrom.toInstant());
        assertThat(policyVersion.getEffectiveTo().toInstant()).isEqualTo(effectiveTo.toInstant());
        assertThat(policyVersion.getCreatedAt().toInstant()).isEqualTo(createdAt.toInstant());

        MvcResult result = mvc.perform(get(URL + "/11"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(OffsetDateTime.parse(response.get("effectiveFrom").asText()).toInstant())
                .isEqualTo(effectiveFrom.toInstant());
        assertThat(OffsetDateTime.parse(response.get("effectiveTo").asText()).toInstant())
                .isEqualTo(effectiveTo.toInstant());
        assertThat(OffsetDateTime.parse(response.get("createdAt").asText()).toInstant())
                .isEqualTo(createdAt.toInstant());
    }

    @Test
    void createsFirstVersionAndReturnsDatabaseValuesInListAndDetail() throws Exception {
        jdbcTemplate.update("insert into leave_policy_group (policy_id, name) values (3, '새 정책')");

        MvcResult result = mvc.perform(post("/api/v1/leaves/admin/policies/3/versions")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"baseType":"HIRE_DATE","unitMinutes":17,"carryOverAllowed":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.policyId").value(3))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.unitMinutes").value(17))
                .andExpect(jsonPath("$.data.expiryRule").value("END_OF_VALIDITY"))
                .andExpect(jsonPath("$.data.isActive").value(false))
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        long versionId = response.get("policyVersionId").asLong();
        LeavePolicyVersion savedVersion = leavePolicyVersionRepository.findById(versionId).orElseThrow();
        assertThat(savedVersion.getCreatedAt().toInstant())
                .isEqualTo(OffsetDateTime.parse(response.get("createdAt").asText()).toInstant());
        assertThat(savedVersion.getExpiryRule()).isEqualTo("END_OF_VALIDITY");
        mvc.perform(get("/api/v1/leaves/admin/policies/3/versions"))
                .andExpect(jsonPath("$.data[0].policyVersionId").value(versionId));
        mvc.perform(get("/api/v1/leaves/admin/policies/3/versions/" + versionId))
                .andExpect(content().json(result.getResponse().getContentAsString(), JsonCompareMode.STRICT));
        for (String table : List.of("leave_accrual_rule", "leave_policy_type_rule", "employee_leave_policy_assignment",
                "leave_request", "leave_grant")) {
            assertThat(jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class)).isZero();
        }
    }

    @Test
    void allocatesNextVersionIndependentlyPerGroupAndPreservesActivePointer() throws Exception {
        jdbcTemplate.update("delete from leave_policy_version where policy_version_id = 13");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id = 11 where policy_id = 1");
        String request = "{\"baseType\":\"HIRE_DATE\",\"unitMinutes\":30,\"carryOverAllowed\":false}";

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNo").value(4))
                .andExpect(jsonPath("$.data.isActive").value(false));
        mvc.perform(post("/api/v1/leaves/admin/policies/2/versions")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNo").value(2));
        assertThat(jdbcTemplate.queryForObject(
                "select active_policy_version_id from leave_policy_group where policy_id = 1", Long.class
        )).isEqualTo(11L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"CUSTOM_RULE\"", "\"  CUSTOM_RULE  \""})
    void defaultsNullExpiryRuleAndPreservesExplicitValues(String expiryJson) throws Exception {
        String expectedExpiryRule = expiryJson.equals("null") ? "END_OF_VALIDITY"
                : objectMapper.readTree(expiryJson).asText();
        String request = """
                {"baseType":"FISCAL_YEAR","fiscalStart":"04-01","unitMinutes":60,
                 "carryOverAllowed":true,"carryOverLimitMinutes":240,"expiryRule":%s}
                """.formatted(expiryJson);

        MvcResult result = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.expiryRule").value(expectedExpiryRule))
                .andExpect(jsonPath("$.data.fiscalStart").value("04-01"))
                .andExpect(jsonPath("$.data.carryOverLimitMinutes").value(240))
                .andReturn();
        long versionId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("policyVersionId").asLong();
        assertThat(leavePolicyVersionRepository.findById(versionId).orElseThrow().getExpiryRule())
                .isEqualTo(expectedExpiryRule);
    }

    @Test
    void allowsCarryOverWithoutLimitAndExpiryRuleAtColumnLength() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"baseType":"HIRE_DATE","unitMinutes":1,"carryOverAllowed":true,"expiryRule":"%s"}
                        """.formatted("A".repeat(50))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.carryOverLimitMinutes").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.expiryRule").value("A".repeat(50)));
    }

    static Stream<Arguments> invalidCreationFields() {
        return Stream.of(
                Arguments.of("baseType", "<omitted>"), Arguments.of("baseType", "null"),
                Arguments.of("baseType", "\"UNKNOWN\""), Arguments.of("fiscalStart", "\"01-01\""),
                Arguments.of("unitMinutes", "<omitted>"), Arguments.of("unitMinutes", "null"),
                Arguments.of("unitMinutes", "0"), Arguments.of("unitMinutes", "-1"),
                Arguments.of("carryOverAllowed", "<omitted>"), Arguments.of("carryOverAllowed", "null"),
                Arguments.of("carryOverLimitMinutes", "1"), Arguments.of("carryOverLimitMinutes", "0"),
                Arguments.of("carryOverLimitMinutes", "-1"), Arguments.of("expiryRule", "\"\""),
                Arguments.of("expiryRule", "\"   \""), Arguments.of("expiryRule", "\"" + "A".repeat(51) + "\""),
                Arguments.of("effectiveFrom", "\"2026-10-21\"")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidCreationFields")
    void rejectsInvalidCreationInputWithoutPersisting(String field, String jsonValue) throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(
                "{\"baseType\":\"HIRE_DATE\",\"unitMinutes\":30,\"carryOverAllowed\":false}"
        );
        if (jsonValue.equals("<omitted>")) {
            request.remove(field);
        } else {
            request.set(field, objectMapper.readTree(jsonValue));
        }

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(leavePolicyVersionRepository.count()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"1-01\"", "\"01/01\"", "\"00-01\"", "\"13-01\"",
            "\"01-00\"", "\"04-31\"", "\"02-30\"", "\"02-29\""})
    void rejectsInvalidFiscalYearStart(String fiscalStart) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"baseType":"FISCAL_YEAR","fiscalStart":%s,"unitMinutes":30,"carryOverAllowed":false}
                        """.formatted(fiscalStart)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-21T04:00:00Z", "2026-10-21T03:59:59Z"})
    void rejectsEqualOrEarlierEndByInstant(String effectiveTo) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"baseType":"HIRE_DATE","unitMinutes":30,"carryOverAllowed":false,
                         "effectiveFrom":"2026-10-21T13:00:00+09:00","effectiveTo":"%s"}
                        """.formatted(effectiveTo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void createsTimestampPeriodUsingActualInstants() throws Exception {
        MvcResult result = mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"baseType":"HIRE_DATE","unitMinutes":30,"carryOverAllowed":false,
                         "effectiveFrom":"2026-10-21T13:00:00+09:00","effectiveTo":"2026-10-21T05:00:00Z"}
                        """))
                .andExpect(status().isCreated()).andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        LeavePolicyVersion version = leavePolicyVersionRepository
                .findById(response.get("policyVersionId").asLong()).orElseThrow();
        assertThat(version.getEffectiveFrom().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-21T04:00:00Z").toInstant());
        assertThat(version.getEffectiveTo().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-21T05:00:00Z").toInstant());
        assertThat(OffsetDateTime.parse(response.get("effectiveFrom").asText()).toInstant())
                .isEqualTo(version.getEffectiveFrom().toInstant());
    }

    @Test
    void returns404ForCreationInMissingGroupAnd403ForNonAdmin() throws Exception {
        String request = "{\"baseType\":\"HIRE_DATE\",\"unitMinutes\":30,\"carryOverAllowed\":false}";
        mvc.perform(post("/api/v1/leaves/admin/policies/9999/versions")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_004"));
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        assertThat(leavePolicyVersionRepository.count()).isEqualTo(4);
    }

    @Test
    void rollsBackFailedInsertWithoutLeavingPartialData() {
        LeavePolicyVersionCreateRequest request = new LeavePolicyVersionCreateRequest(
                PolicyBaseType.HIRE_DATE, null, 30, false, null, "A".repeat(51), null, null
        );

        assertThatThrownBy(() -> leavePolicyVersionService.createPolicyVersion(1L, request))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(leavePolicyVersionRepository.count()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_accrual_rule", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_policy_type_rule", Integer.class)).isZero();
    }

    @Test
    void serializesConcurrentCreationWithPostgresGroupLock() throws Exception {
        int taskCount = 6;
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        LeavePolicyVersionCreateRequest request = new LeavePolicyVersionCreateRequest(
                PolicyBaseType.HIRE_DATE, null, 30, false, null, null, null, null
        );
        List<LeavePolicyVersionDetailResponse> responses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(taskCount)) {
            List<Future<LeavePolicyVersionDetailResponse>> futures = new ArrayList<>();
            for (int index = 0; index < taskCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 생성 시작 대기 시간 초과");
                    }
                    return leavePolicyVersionService.createPolicyVersion(1L, request);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<LeavePolicyVersionDetailResponse> future : futures) {
                responses.add(future.get(20, TimeUnit.SECONDS));
            }
        }

        assertThat(responses).extracting(LeavePolicyVersionDetailResponse::versionNo)
                .containsExactlyInAnyOrder(4, 5, 6, 7, 8, 9);
        assertThat(responses).extracting(LeavePolicyVersionDetailResponse::policyVersionId).doesNotHaveDuplicates();
        assertThat(leavePolicyVersionRepository.findAllByPolicyGroupPolicyIdOrderByVersionNoAsc(1L))
                .extracting(LeavePolicyVersion::getVersionNo).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveLimitEvenWhenCarryOverIsAllowed(int limitMinutes) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"baseType":"HIRE_DATE","unitMinutes":30,"carryOverAllowed":true,"carryOverLimitMinutes":%d}
                        """.formatted(limitMinutes)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"effectiveFrom", "effectiveTo"})
    void allowsEitherPeriodEndpointToBeOmitted(String periodField) throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"baseType":"HIRE_DATE","unitMinutes":30,"carryOverAllowed":false,
                         "%s":"2026-10-21T04:00:00Z"}
                        """.formatted(periodField)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data." + periodField).value("2026-10-21T04:00:00Z"));
    }

    @Test
    void rejectsVersionNumberOverflowWithoutChangingExistingVersions() throws Exception {
        jdbcTemplate.update("update leave_policy_version set version_no = ? where policy_version_id = 12", Integer.MAX_VALUE);

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
                        {"baseType":"HIRE_DATE","unitMinutes":30,"carryOverAllowed":false}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        assertThat(leavePolicyVersionRepository.count()).isEqualTo(4);
    }
}
