package com.ssafy.leaveservice.domain.leavetype;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.domain.leavetype.dto.LeaveTypeUpdateRequest;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.leavetype.service.LeaveTypeService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=LeaveTypePostgresIntegrationTest",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/",
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeaveTypePostgresIntegrationTest {

    private static final String URL = "/api/v1/leaves/admin/types";
    private static final String VALID_REQUEST = """
            {
              "name": "병가",
              "isPaid": false,
              "balanceMode": "NONE",
              "requestUnit": "DAY",
              "maxMinutes": 480
            }
            """;

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LeaveTypeRepository leaveTypeRepository;

    @Autowired
    private LeaveTypeService leaveTypeService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmployeeAccess employeeAccess;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        jdbcTemplate.update("delete from leave_request");
        jdbcTemplate.update("delete from leave_policy_type_rule");
        jdbcTemplate.update("delete from leave_policy_version");
        jdbcTemplate.update("delete from leave_policy_group");
        jdbcTemplate.update("delete from leave_type");
        jdbcTemplate.update("delete from leave_balance_type");
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
    }

    @Test
    void createsTypeAndReturnsPersistedDataFromGet() throws Exception {
        MvcResult createResult = mvc.perform(post(URL)
                        .contentType(APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.name").value("병가"))
                .andExpect(jsonPath("$.data.paid").value(false))
                .andExpect(jsonPath("$.data.balanceMode").value("NONE"))
                .andExpect(jsonPath("$.data.requestUnit").value("DAY"))
                .andExpect(jsonPath("$.data.maxMinutes").value(480))
                .andExpect(jsonPath("$.data.balanceType").value(nullValue()))
                .andExpect(jsonPath("$.data.isActive").value(true))
                .andReturn();

        JsonNode createdType = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("data");
        long leaveTypeId = createdType.get("leaveTypeId").asLong();
        assertThat(leaveTypeId).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "select is_active from leave_type where leave_type_id = ?", Boolean.class, leaveTypeId
        )).isTrue();

        MvcResult getResult = mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn();
        JsonNode returnedType = objectMapper.readTree(getResult.getResponse().getContentAsString()).get("data").get(0);
        assertThat(returnedType).isEqualTo(createdType);
    }

    @Test
    void createsAccountTypeWithBalanceAndExplicitInactiveState() throws Exception {
        Long balanceTypeId = jdbcTemplate.queryForObject(
                "insert into leave_balance_type (name, is_active) values ('연차', false) returning balance_type_id",
                Long.class
        );
        ObjectNode request = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        request.put("balanceTypeId", balanceTypeId);
        request.put("balanceMode", "ACCOUNT");
        request.put("isPaid", true);
        request.put("isActive", false);
        request.putNull("maxMinutes");

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.balanceType.balanceTypeId").value(balanceTypeId))
                .andExpect(jsonPath("$.data.balanceType.active").value(false))
                .andExpect(jsonPath("$.data.paid").value(true))
                .andExpect(jsonPath("$.data.maxMinutes").value(nullValue()))
                .andExpect(jsonPath("$.data.isActive").value(false));

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].isActive").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "isPaid", "balanceMode", "requestUnit"})
    void rejectsMissingRequiredField(String fieldName) throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        request.remove(fieldName);

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_type", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"balanceMode", "requestUnit"})
    void rejectsInvalidEnum(String fieldName) throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        request.put(fieldName, "INVALID");

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void rejectsUnknownBalanceType() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        request.put("balanceTypeId", 999999L);

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LEAVE_001"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_type", Long.class)).isZero();
    }

    @Test
    void rejectsAccountWithoutBalanceType() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        request.put("balanceMode", "ACCOUNT");

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.error.message").value("ACCOUNT 유형은 잔액 유형 ID가 필수입니다."));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveMaxMinutes(int maxMinutes) throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        request.put("maxMinutes", maxMinutes);

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void rejectsNonAdminWithoutSaving() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_type", Long.class)).isZero();
    }

    @Test
    void returnsEmptyGetResponse() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankName(String name) throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        request.put("name", name);

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void rejectsNameLongerThanDatabaseColumn() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree(VALID_REQUEST);
        request.put("name", "가".repeat(101));

        mvc.perform(post(URL).contentType(APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void v2PreservesExistingRowsAndAddsDefaultAndNotNullConstraint() {
        String migrationSchema = "migration_upgrade";
        Flyway versionOne = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(migrationSchema)
                .defaultSchema(migrationSchema)
                .target("1")
                .load();
        versionOne.migrate();

        String jdbcUrl = postgres.getJdbcUrl();
        String schemaJdbcUrl = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + migrationSchema;
        DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(
                schemaJdbcUrl,
                postgres.getUsername(),
                postgres.getPassword()
        );
        JdbcTemplate migrationJdbcTemplate = new JdbcTemplate(migrationDataSource);
        migrationJdbcTemplate.update("insert into leave_type (name, balance_mode) values ('기존 휴가', 'NONE')");

        Flyway versionTwo = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(migrationSchema)
                .defaultSchema(migrationSchema)
                .load();
        versionTwo.migrate();

        assertThat(migrationJdbcTemplate.queryForObject(
                "select is_active from leave_type where name = '기존 휴가'", Boolean.class
        )).isTrue();
        migrationJdbcTemplate.update("insert into leave_type (name, balance_mode) values ('기본값 확인', 'NONE')");
        assertThat(migrationJdbcTemplate.queryForObject(
                "select is_active from leave_type where name = '기본값 확인'", Boolean.class
        )).isTrue();
        assertThatThrownBy(() -> migrationJdbcTemplate.update(
                "insert into leave_type (name, balance_mode, is_active) values ('NULL 불가', 'NONE', null)"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void patchesAllFieldsOfUnreferencedTypeAndReturnsThemFromGet() throws Exception {
        Long leaveTypeId = createUnreferencedType();
        Long balanceTypeId = jdbcTemplate.queryForObject(
                "insert into leave_balance_type (name) values ('연차') returning balance_type_id", Long.class
        );
        String request = """
                {
                  "name":"시간 연차", "isPaid":true, "balanceMode":"ACCOUNT",
                  "balanceTypeId":%d, "requestUnit":"HOUR", "maxMinutes":120, "isActive":false
                }
                """.formatted(balanceTypeId);

        MvcResult patchResult = mvc.perform(patch(URL + "/" + leaveTypeId)
                        .contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.leaveTypeId").value(leaveTypeId))
                .andExpect(jsonPath("$.data.name").value("시간 연차"))
                .andExpect(jsonPath("$.data.paid").value(true))
                .andExpect(jsonPath("$.data.balanceMode").value("ACCOUNT"))
                .andExpect(jsonPath("$.data.balanceType.balanceTypeId").value(balanceTypeId))
                .andExpect(jsonPath("$.data.requestUnit").value("HOUR"))
                .andExpect(jsonPath("$.data.maxMinutes").value(120))
                .andExpect(jsonPath("$.data.isActive").value(false))
                .andReturn();

        MvcResult getResult = mvc.perform(get(URL)).andExpect(status().isOk()).andReturn();
        JsonNode patchedType = objectMapper.readTree(patchResult.getResponse().getContentAsString()).get("data");
        JsonNode listedType = objectMapper.readTree(getResult.getResponse().getContentAsString()).get("data").get(0);
        assertThat(listedType).isEqualTo(patchedType);
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_type", Long.class)).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "CANCELLED", "REJECTED", "POLICY"})
    void permitsNameAndActiveChangesWithoutDeletingReferences(String referenceKind) throws Exception {
        Long leaveTypeId = createUnreferencedType();
        createReference(leaveTypeId, referenceKind);

        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"표시명 변경\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("표시명 변경"))
                .andExpect(jsonPath("$.data.maxMinutes").value(480))
                .andExpect(jsonPath("$.data.paid").value(false))
                .andExpect(jsonPath("$.data.balanceMode").value("NONE"))
                .andExpect(jsonPath("$.data.requestUnit").value("DAY"))
                .andExpect(jsonPath("$.data.isActive").value(true));

        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("표시명 변경"))
                .andExpect(jsonPath("$.data.isActive").value(false));
        assertThat(leaveTypeRepository.existsReferences(leaveTypeId)).isTrue();
    }

    static Stream<Arguments> restrictedChanges() {
        List<Arguments> cases = new ArrayList<>();
        for (String referenceKind : List.of("DRAFT", "CANCELLED", "REJECTED", "POLICY")) {
            for (String change : List.of(
                    "\"isPaid\":true", "\"balanceMode\":\"ACCOUNT\"", "\"balanceTypeId\":999999",
                    "\"requestUnit\":\"HOUR\"", "\"maxMinutes\":120", "\"maxMinutes\":null"
            )) {
                cases.add(Arguments.of(referenceKind, change));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("restrictedChanges")
    void rejectsEntireMixedPatchWhenProtectedSettingsChange(String referenceKind, String change) throws Exception {
        Long leaveTypeId = createUnreferencedType();
        createReference(leaveTypeId, referenceKind);
        MvcResult before = mvc.perform(get(URL)).andReturn();

        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"반영되면 안 됨\",\"isActive\":false," + change + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LEAVE_003"));

        MvcResult after = mvc.perform(get(URL)).andReturn();
        assertThat(objectMapper.readTree(after.getResponse().getContentAsString()))
                .isEqualTo(objectMapper.readTree(before.getResponse().getContentAsString()));
    }

    @Test
    void permitsSameProtectedValuesOnReferencedType() throws Exception {
        Long leaveTypeId = createUnreferencedType();
        createReference(leaveTypeId, "POLICY");

        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON).content("""
                        {"isPaid":false,"balanceMode":"NONE","balanceTypeId":null,"requestUnit":"DAY","maxMinutes":480}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.leaveTypeId").value(leaveTypeId));
    }

    @Test
    void distinguishesOmissionFromNullWhenRemovingExistingValues() throws Exception {
        Long leaveTypeId = createUnreferencedType();
        Long balanceTypeId = jdbcTemplate.queryForObject(
                "insert into leave_balance_type (name) values ('선택 잔액') returning balance_type_id", Long.class
        );
        jdbcTemplate.update("update leave_type set balance_type_id = ? where leave_type_id = ?", balanceTypeId, leaveTypeId);

        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON).content("{\"name\":\"이름만 변경\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balanceType.balanceTypeId").value(balanceTypeId))
                .andExpect(jsonPath("$.data.maxMinutes").value(480));

        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON)
                        .content("{\"balanceTypeId\":null,\"maxMinutes\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balanceType").value(nullValue()))
                .andExpect(jsonPath("$.data.maxMinutes").value(nullValue()));
    }

    @Test
    void rejectsRemovingBalanceFromAccountAndAllowsAtomicModeSwitch() throws Exception {
        Long leaveTypeId = createUnreferencedType();
        Long balanceTypeId = jdbcTemplate.queryForObject(
                "insert into leave_balance_type (name) values ('필수 잔액') returning balance_type_id", Long.class
        );
        jdbcTemplate.update("update leave_type set balance_mode = 'ACCOUNT', balance_type_id = ? where leave_type_id = ?",
                balanceTypeId, leaveTypeId);

        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"실패 시 유지\",\"balanceTypeId\":null}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("select name from leave_type where leave_type_id = ?", String.class, leaveTypeId))
                .isEqualTo("기존 유형");

        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON)
                        .content("{\"balanceMode\":\"NONE\",\"balanceTypeId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balanceMode").value("NONE"))
                .andExpect(jsonPath("$.data.balanceType").value(nullValue()));
    }

    @Test
    void rejectsMissingBalanceAndUnbalancedAccountWithoutPartialChanges() throws Exception {
        Long leaveTypeId = createUnreferencedType();
        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"반영 금지\",\"balanceTypeId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LEAVE_001"));
        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON)
                        .content("{\"balanceMode\":\"ACCOUNT\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("select name from leave_type where leave_type_id = ?", String.class, leaveTypeId))
                .isEqualTo("기존 유형");
    }

    @Test
    void returns404ForMissingTypeAnd403ForNonAdmin() throws Exception {
        mvc.perform(patch(URL + "/999999").contentType(APPLICATION_JSON).content("{\"isActive\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LEAVE_002"));

        Long leaveTypeId = createUnreferencedType();
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON).content("{\"isActive\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
        assertThat(jdbcTemplate.queryForObject("select is_active from leave_type where leave_type_id = ?", Boolean.class, leaveTypeId))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"unknown\":true}", "{\"name\":\"\"}", "{\"name\":\"   \"}", "{\"name\":null}",
            "{\"isPaid\":null}", "{\"balanceMode\":null}", "{\"requestUnit\":null}", "{\"isActive\":null}",
            "{\"balanceMode\":\"INVALID\"}", "{\"requestUnit\":\"INVALID\"}", "{\"maxMinutes\":0}",
            "{\"maxMinutes\":-1}", "{\"maxMinutes\":1.5}", "{\"isPaid\":\"true\"}", "{\"balanceMode\":0}"
    })
    void rejectsInvalidPatch(String request) throws Exception {
        Long leaveTypeId = createUnreferencedType();
        mvc.perform(patch(URL + "/" + leaveTypeId).contentType(APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    private Long createUnreferencedType() {
        return jdbcTemplate.queryForObject("""
                insert into leave_type (name, is_paid, balance_mode, request_unit, max_minutes)
                values ('기존 유형', false, 'NONE', 'DAY', 480) returning leave_type_id
                """, Long.class);
    }

    private Long createPolicyVersion() {
        Long policyId = jdbcTemplate.queryForObject(
                "insert into leave_policy_group (name) values ('테스트 정책') returning policy_id", Long.class
        );
        return jdbcTemplate.queryForObject("""
                insert into leave_policy_version (policy_id, version_no, effective_from, base_type)
                values (?, 1, current_timestamp, 'HIRE_DATE') returning policy_version_id
                """, Long.class, policyId);
    }

    private void createReference(Long leaveTypeId, String referenceKind) {
        Long policyVersionId = createPolicyVersion();
        if ("POLICY".equals(referenceKind)) {
            jdbcTemplate.update("insert into leave_policy_type_rule (policy_version_id, leave_type_id) values (?, ?)",
                    policyVersionId, leaveTypeId);
            return;
        }
        jdbcTemplate.update("insert into leave_request (policy_version_id, leave_type_id, emp_id, status) values (?, ?, 42, ?)",
                policyVersionId, leaveTypeId, referenceKind);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUEST", "POLICY"})
    void patchRowLockBlocksBothKindsOfNewForeignKeyReference(String referenceKind) throws Exception {
        Long leaveTypeId = createUnreferencedType();
        Long policyVersionId = createPolicyVersion();
        String insertReference = referenceInsertSql(referenceKind);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            leaveTypeRepository.findByIdForUpdate(leaveTypeId).orElseThrow();
            assertThatThrownBy(() -> {
                try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                    connection.createStatement().execute("set lock_timeout = '300ms'");
                    try (PreparedStatement insert = connection.prepareStatement(insertReference)) {
                        insert.setLong(1, policyVersionId);
                        insert.setLong(2, leaveTypeId);
                        insert.executeUpdate();
                    }
                }
            }).isInstanceOfSatisfying(SQLException.class, exception -> assertThat(exception.getSQLState()).isEqualTo("55P03"));
        });

        jdbcTemplate.update(insertReference, policyVersionId, leaveTypeId);
        assertThat(leaveTypeRepository.existsReferences(leaveTypeId)).isTrue();
    }

    @Test
    void patchSeesReferenceCommittedWhileWaitingForRowLock() throws Exception {
        Long leaveTypeId = createUnreferencedType();
        Long policyVersionId = createPolicyVersion();
        LeaveTypeUpdateRequest request = objectMapper.readValue("{\"isPaid\":true}", LeaveTypeUpdateRequest.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection referenceConnection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            referenceConnection.setAutoCommit(false);
            try (PreparedStatement insert = referenceConnection.prepareStatement(referenceInsertSql("REQUEST"))) {
                insert.setLong(1, policyVersionId);
                insert.setLong(2, leaveTypeId);
                insert.executeUpdate();
            }

            try {
                Future<?> patch = executor.submit(() -> leaveTypeService.updateType(leaveTypeId, request));
                await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                        select exists (select 1 from pg_stat_activity
                        where datname = current_database() and wait_event_type = 'Lock'
                          and query ilike '%from leave_type%for update%')
                        """, Boolean.class)));
                referenceConnection.commit();

                assertThatThrownBy(() -> patch.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(BusinessException.class)
                        .cause().isInstanceOfSatisfying(BusinessException.class,
                                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LEAVE_TYPE_IN_USE));
            } finally {
                referenceConnection.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbcTemplate.queryForObject("select is_paid from leave_type where leave_type_id = ?", Boolean.class, leaveTypeId))
                .isFalse();
    }

    private String referenceInsertSql(String referenceKind) {
        if ("POLICY".equals(referenceKind)) {
            return "insert into leave_policy_type_rule (policy_version_id, leave_type_id) values (?, ?)";
        }
        return "insert into leave_request (policy_version_id, leave_type_id, emp_id) values (?, ?, 42)";
    }
}
