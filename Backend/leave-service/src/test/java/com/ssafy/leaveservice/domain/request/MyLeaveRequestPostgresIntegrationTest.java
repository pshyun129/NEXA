package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.hibernate.resource.jdbc.spi.StatementInspector;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.OffsetDateTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "leave.test.database-owner=MyLeaveRequestPostgresIntegrationTest",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.request.MyLeaveRequestPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class MyLeaveRequestPostgresIntegrationTest {

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<List<Boolean>> readOnlyFlags = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<List<Integer>> isolationLevels = ThreadLocal.withInitial(ArrayList::new);
        private static final ThreadLocal<Boolean> gateQuery = ThreadLocal.withInitial(() -> false);

        public SqlCapture() {
        }

        @Override
        public String inspect(String sql) {
            statements.get().add(sql.toLowerCase());
            readOnlyFlags.get().add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            isolationLevels.get().add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            // 격리된 테스트에서만 SELECT 실행 중의 동시 커밋을 제어한다.
            if (gateQuery.get() && sql.contains("from leave_request ")) {
                gateQuery.remove();
                // pg_stat_activity는 SQL 앞부분만 보관하므로 컬럼 증가에도 게이트 대기를 식별한다.
                String gateMarker = "/* request_query_test_gate() */ ";
                if (sql.contains(" where ")) {
                    return gateMarker + sql.replace(" where ", " where request_query_test_gate() and ");
                }
                return gateMarker + sql.replace(" order by ", " where request_query_test_gate() order by ");
            }
            return sql;
        }
    }

    private static final String URL = "/api/v1/leaves/me/requests";
    private static final String ADMIN_URL = "/api/v1/leaves/admin/requests";
    private static final List<String> TABLES = List.of(
            "leave_ledger", "leave_request_date", "leave_request", "leave_grant", "leave_accrual_rule", "leave_policy_type_rule",
            "employee_leave_policy_assignment", "leave_account", "leave_type", "leave_balance_type", "leave_policy_version", "leave_policy_group"
    );

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired private WebApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private EmployeeAccess employeeAccess;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        SqlCapture.statements.get().clear();
        SqlCapture.readOnlyFlags.get().clear();
        SqlCapture.isolationLevels.get().clear();
        SqlCapture.gateQuery.remove();
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(false);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : TABLES) {
            jdbcTemplate.update("delete from " + table);
        }
        jdbcTemplate.update("insert into leave_policy_group (policy_id,name) values (1,'기존 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version (policy_version_id,policy_id,version_no,base_type)
                values (11,1,1,'HIRE_DATE'),(12,1,2,'HIRE_DATE')
                """);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=12 where policy_id=1");
        jdbcTemplate.update("insert into leave_balance_type (balance_type_id,name,is_active) values (1,'연차',false)");
        jdbcTemplate.update("""
                insert into leave_type (leave_type_id,balance_type_id,name,balance_mode,request_unit,is_active)
                values (1,1,'현재 연차명','ACCOUNT','MIXED',false)
                """);
        jdbcTemplate.update("""
                insert into leave_request (leave_req_id,policy_version_id,leave_type_id,emp_id,status,created_at,updated_at)
                values (10,11,1,42,'DRAFT','2020-01-01Z','2020-01-01Z'),
                       (20,11,1,42,'SUBMITTED','2021-01-01Z','2021-01-01Z'),
                       (30,11,1,42,'APPROVED','2022-01-01Z','2022-01-01Z'),
                       (40,11,1,42,'REJECTED','2023-01-01Z','2023-01-01Z'),
                       (50,11,1,42,'CANCELLED','2026-01-01Z','2026-01-05Z'),
                       (60,11,1,42,'WITHDRAWN','2026-01-01Z','2026-01-01Z'),
                       (99,11,1,84,'APPROVED','2099-01-01Z','2099-01-01Z')
                """);
        jdbcTemplate.update("""
                update leave_request set approval_doc_id=90050,total_minutes=999,total_deduct_minutes=333,
                    reason=E'  개인 일정\n보존  ',submitted_at='2026-01-02Z',cancelled_at='2026-01-04Z',
                    cancelled_by=42,cancel_reason='  취소 사유  ' where leave_req_id=50
                """);
        jdbcTemplate.update("""
                insert into leave_request_date (request_date_id,leave_req_id,leave_date,portion,start_at,end_at,
                                                scheduled_work_minutes,requested_minutes,deduct_minutes)
                values (501,50,'2030-01-01T00:00:00+09:00','FULL',null,null,480,480,480),
                       (502,50,'2030-01-02T00:00:00+09:00','AM_HALF','2030-01-02T09:00:00+09:00','2030-01-02T13:00:00+09:00',480,240,240),
                       (503,50,'2030-01-04T00:00:00+09:00','PM_HALF','2030-01-04T13:00:00+09:00','2030-01-04T17:00:00+09:00',480,240,240),
                       (505,50,'2030-01-04T00:00:00+09:00','HOURLY','2030-01-04T09:00:00+09:00','2030-01-04T11:00:00+09:00',0,120,0),
                       (504,50,'2030-01-04T00:00:00+09:00','HOURLY','2030-01-04T09:00:00+09:00','2030-01-04T10:00:00+09:00',null,60,30),
                       (401,40,'2030-01-01T00:00:00+09:00','AM_HALF',null,null,null,240,120)
                """);
        jdbcTemplate.update("insert into leave_account (account_id,emp_id,balance_type_id) values (901,42,1)");
        jdbcTemplate.update("""
                insert into leave_grant (grant_id,account_id,policy_version_id,grant_kind,original_minutes,valid_from)
                values (5,901,11,'MANUAL',480,'2000-01-01Z')
                """);
        jdbcTemplate.update("""
                insert into leave_ledger (entry_id,account_id,grant_id,leave_req_id,event_type,quantity_delta,idempotency_key)
                values (500,901,5,50,'GRANT',480,'request-history')
                """);
    }

    @Test
    void listReturnsAllSixStatusesAndOneRowPerRequestWithoutChangingHistory() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        JsonNode result = response(URL);
        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(result.get("error").isNull()).isTrue();
        JsonNode requests = result.get("data");
        assertThat(ids(requests, "requestId")).containsExactly(60L, 50L, 40L, 30L, 20L, 10L);
        List<LeaveRequestStatus> statuses = new ArrayList<>();
        for (JsonNode request : requests) {
            statuses.add(LeaveRequestStatus.valueOf(request.get("status").asString()));
            assertThat(request.get("policyVersionId").asLong()).isEqualTo(11);
            assertThat(request.get("leaveTypeName").asString()).isEqualTo("현재 연차명");
        }
        assertThat(statuses).containsExactlyInAnyOrder(LeaveRequestStatus.DRAFT, LeaveRequestStatus.SUBMITTED, LeaveRequestStatus.APPROVED,
                LeaveRequestStatus.REJECTED, LeaveRequestStatus.WITHDRAWN, LeaveRequestStatus.CANCELLED);
        assertThat(requests.get(1).get("totalMinutes").asInt()).isEqualTo(999);
        assertThat(requests.get(1).get("totalDeductMinutes").asInt()).isEqualTo(333);
        assertThat(requests.get(5).get("submittedAt").isNull()).isTrue();
        assertSingleRead();
        assertThat(SqlCapture.statements.get().getFirst()).doesNotContain("leave_request_date", "leave_ledger", "join leave_policy");
        assertThat(storedRows()).isEqualTo(before);
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void listNoRecordsReturnsEmptyApiResult() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenReturn(12345L);
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}"));
    }

    @Test
    void listSingleRecordAndCurrentMasterRenameAreReflected() throws Exception {
        when(employeeAccess.currentEmployeeId()).thenReturn(84L);
        jdbcTemplate.update("update leave_type set name='변경된 유형명' where leave_type_id=1");
        JsonNode requests = response(URL).get("data");
        assertThat(ids(requests, "requestId")).containsExactly(99L);
        assertThat(requests.get(0).get("leaveTypeName").asString()).isEqualTo("변경된 유형명");
    }

    @Test
    void listIgnoresClientIdentityAndPreservesHistoryAfterActivePointerChange() throws Exception {
        JsonNode before = response(URL);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        MvcResult result = mvc.perform(get(URL).param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())).isEqualTo(before);
    }

    @Test
    void detailReturnsAllStoredFieldsAndSortedDatesInOneReadWithoutWrites() throws Exception {
        Map<String, List<Map<String, Object>>> before = storedRows();
        JsonNode detail = response(URL + "/50").get("data");
        assertThat(detail.propertyNames()).containsExactlyInAnyOrder(
                "requestId", "employeeId", "policyVersionId", "leaveTypeId", "leaveTypeName", "approvalDocId", "status",
                "totalMinutes", "totalDeductMinutes", "reason", "submittedAt", "cancelledAt", "cancelledBy", "cancelReason",
                "createdAt", "updatedAt", "dates");
        assertThat(detail.get("requestId").asLong()).isEqualTo(50);
        assertThat(detail.get("employeeId").asLong()).isEqualTo(42);
        assertThat(detail.get("policyVersionId").asLong()).isEqualTo(11);
        assertThat(detail.get("leaveTypeId").asLong()).isEqualTo(1);
        assertThat(detail.get("leaveTypeName").asString()).isEqualTo("현재 연차명");
        assertThat(detail.get("approvalDocId").asLong()).isEqualTo(90050);
        assertThat(detail.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(detail.get("totalMinutes").asInt()).isEqualTo(999);
        assertThat(detail.get("totalDeductMinutes").asInt()).isEqualTo(333);
        assertThat(detail.get("reason").asString()).isEqualTo("  개인 일정\n보존  ");
        assertThat(detail.get("cancelledBy").asLong()).isEqualTo(42);
        assertThat(detail.get("cancelReason").asString()).isEqualTo("  취소 사유  ");
        assertInstant(detail, "createdAt", "2026-01-01T00:00:00Z");
        assertInstant(detail, "submittedAt", "2026-01-02T00:00:00Z");
        assertInstant(detail, "cancelledAt", "2026-01-04T00:00:00Z");
        assertInstant(detail, "updatedAt", "2026-01-05T00:00:00Z");
        JsonNode dates = detail.get("dates");
        assertThat(ids(dates, "requestDateId")).containsExactly(501L, 502L, 504L, 505L, 503L);
        List<LeaveRequestPortion> portions = new ArrayList<>();
        for (JsonNode date : dates) {
            portions.add(LeaveRequestPortion.valueOf(date.get("portion").asString()));
        }
        assertThat(portions).contains(LeaveRequestPortion.values());
        assertThat(dates.get(0).get("startAt").isNull()).isTrue();
        assertThat(dates.get(0).get("endAt").isNull()).isTrue();
        assertThat(dates.get(0).get("scheduledWorkMinutes").asInt()).isEqualTo(480);
        assertThat(dates.get(0).get("requestedMinutes").asInt()).isEqualTo(480);
        assertThat(dates.get(2).get("scheduledWorkMinutes").isNull()).isTrue();
        assertThat(dates.get(2).get("requestedMinutes").asInt()).isEqualTo(60);
        assertThat(dates.get(2).get("deductMinutes").asInt()).isEqualTo(30);
        assertThat(dates.get(3).get("scheduledWorkMinutes").asInt()).isZero();
        assertThat(dates.get(3).get("deductMinutes").asInt()).isZero();
        assertInstant(dates.get(0), "leaveDate", "2030-01-01T00:00:00+09:00");
        assertInstant(dates.get(2), "startAt", "2030-01-04T09:00:00+09:00");
        assertInstant(dates.get(2), "endAt", "2030-01-04T10:00:00+09:00");
        assertSingleRead();
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void detailOneDateAndNoDatesPreserveNullableFields() throws Exception {
        JsonNode oneDate = response(URL + "/40").get("data");
        assertThat(oneDate.get("dates").size()).isEqualTo(1);
        assertThat(oneDate.get("dates").get(0).get("scheduledWorkMinutes").isNull()).isTrue();
        JsonNode emptyDates = response(URL + "/10").get("data");
        assertThat(emptyDates.get("dates").isEmpty()).isTrue();
        for (String field : List.of("approvalDocId", "reason", "submittedAt", "cancelledAt", "cancelledBy", "cancelReason")) {
            assertThat(emptyDates.get(field).isNull()).isTrue();
        }
    }

    @Test
    void detailMissingAndOtherEmployeeRequestHaveIdentical404EvenForAdmin() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        MvcResult missing = mvc.perform(get(URL + "/12345")).andExpect(status().isNotFound()).andReturn();
        MvcResult otherEmployee = mvc.perform(get(URL + "/99").param("employeeId", "84").header("X-Employee-Id", "84"))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(objectMapper.readTree(otherEmployee.getResponse().getContentAsByteArray()))
                .isEqualTo(objectMapper.readTree(missing.getResponse().getContentAsByteArray()));
        assertThat(SqlCapture.statements.get()).hasSize(2).allSatisfy(sql -> assertThat(sql).contains("emp_id", "leave_req_id"));
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void detailInvalidIdReturns400WithoutReadingDatabase(String requestId) throws Exception {
        mvc.perform(get(URL + "/" + requestId)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @Test
    void detailPolicyChangesDoNotReplaceHistoricalVersionOrStoredState() throws Exception {
        JsonNode before = response(URL + "/50");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        assertThat(response(URL + "/50")).isEqualTo(before);
    }

    @Test
    void adminListReturnsAllEmployeesSixStatusesAndMatchesUserFieldsInOneRead() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        Map<String, List<Map<String, Object>>> before = storedRows();
        JsonNode result = response(ADMIN_URL);
        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(result.get("error").isNull()).isTrue();
        JsonNode requests = result.get("data");
        assertThat(ids(requests, "requestId")).containsExactly(99L, 60L, 50L, 40L, 30L, 20L, 10L);
        assertThat(requests.get(0).get("employeeId").asLong()).isEqualTo(84);
        assertThat(ids(requests, "employeeId")).containsOnly(42L, 84L);
        assertSingleRead();
        assertThat(SqlCapture.statements.get().getFirst()).doesNotContain("leave_request_date", "leave_ledger", "join leave_policy");
        JsonNode userRequests = response(URL).get("data");
        for (int index = 0; index < userRequests.size(); index++) {
            JsonNode userRequest = userRequests.get(index);
            JsonNode adminRequest = requests.get(index + 1);
            assertThat(adminRequest.size()).isEqualTo(userRequest.size() + 1);
            for (String field : userRequest.propertyNames()) {
                assertThat(adminRequest.get(field)).as(field).isEqualTo(userRequest.get(field));
            }
            assertThat(adminRequest.get("employeeId").asLong()).isEqualTo(42);
        }
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void adminListEmptyDatabaseReturnsEmptyArray() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        jdbcTemplate.update("delete from leave_ledger");
        jdbcTemplate.update("delete from leave_request_date");
        jdbcTemplate.update("delete from leave_request");
        mvc.perform(get(ADMIN_URL)).andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"error\":null,\"data\":[]}"));
        assertSingleRead();
    }

    @Test
    void adminListIgnoresIdentityAndStatusFiltersAndCurrentActivePointer() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        JsonNode before = response(ADMIN_URL);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        MvcResult result = mvc.perform(get(ADMIN_URL).param("employeeId", "84").param("status", "APPROVED")
                        .header("X-Employee-Id", "84")).andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())).isEqualTo(before);
    }

    @Test
    void adminListConcurrentCreationUsesSingleStatementSnapshot() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        createGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = writerHoldingGate()) {
            Future<JsonNode> pending = executor.submit(() -> gatedResponse(ADMIN_URL));
            awaitGateWait();
            writer.createStatement().executeUpdate("""
                    insert into leave_request (leave_req_id,policy_version_id,leave_type_id,emp_id,status,created_at)
                    values (70,11,1,84,'SUBMITTED','2098-01-01Z')
                    """);
            writer.commit();
            assertThat(ids(pending.get(10, TimeUnit.SECONDS).get("data"), "requestId"))
                    .containsExactly(99L, 60L, 50L, 40L, 30L, 20L, 10L);
            assertThat(ids(response(ADMIN_URL).get("data"), "requestId"))
                    .containsExactly(99L, 70L, 60L, 50L, 40L, 30L, 20L, 10L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void adminDetailMatchesUserDetailWithAllDatesAndPreservesRowsAndXmin() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        Map<String, List<Map<String, Object>>> before = storedRows();
        JsonNode adminResponse = response(ADMIN_URL + "/50");
        assertSingleRead();
        assertThat(SqlCapture.statements.get().getFirst()).contains("leave_request_date")
                .doesNotContain("leave_ledger", "join leave_policy");
        assertThat(adminResponse).isEqualTo(response(URL + "/50"));
        assertThat(ids(adminResponse.get("data").get("dates"), "requestDateId"))
                .containsExactly(501L, 502L, 504L, 505L, 503L);
        assertThat(storedRows()).isEqualTo(before);
    }

    @Test
    void adminDetailReadsOtherEmployeeButUserEndpointsRemainScopedEvenForAdmin() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        JsonNode otherEmployee = response(ADMIN_URL + "/99").get("data");
        assertThat(otherEmployee.get("employeeId").asLong()).isEqualTo(84);
        assertThat(otherEmployee.get("dates").isEmpty()).isTrue();
        assertThat(otherEmployee.get("approvalDocId").isNull()).isTrue();
        assertThat(ids(response(URL).get("data"), "requestId")).doesNotContain(99L);
        mvc.perform(get(URL + "/99").param("employeeId", "84").header("X-Admin", "true"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMON_003"));
        when(employeeAccess.currentEmployeeId()).thenReturn(84L);
        assertThat(response(URL + "/99").get("data")).isEqualTo(otherEmployee);
    }

    @Test
    void adminDetailPreservesSingleAndEmptyDatesAndHistoricalFields() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        JsonNode oneDate = response(ADMIN_URL + "/40").get("data");
        assertThat(oneDate.get("dates").size()).isEqualTo(1);
        assertThat(oneDate.get("dates").get(0).get("scheduledWorkMinutes").isNull()).isTrue();
        assertThat(oneDate.get("dates").get(0).get("startAt").isNull()).isTrue();
        assertThat(response(ADMIN_URL + "/10").get("data").get("dates").isEmpty()).isTrue();
        JsonNode before = response(ADMIN_URL + "/50");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        assertThat(response(ADMIN_URL + "/50")).isEqualTo(before);
    }

    @Test
    void adminDetailMissingRequestUses404() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        mvc.perform(get(ADMIN_URL + "/12345")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMON_003"));
        assertSingleRead();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "9223372036854775808"})
    void adminDetailInvalidIdsReturn400WithoutReadingDatabase(String requestId) throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        mvc.perform(get(ADMIN_URL + "/" + requestId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @Test
    void adminDetailConcurrentHeaderAndDateChangesUseOneSnapshot() throws Exception {
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        createGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = writerHoldingGate()) {
            Future<JsonNode> pending = executor.submit(() -> gatedResponse(ADMIN_URL + "/50"));
            awaitGateWait();
            writer.createStatement().executeUpdate("update leave_request set status='APPROVED',reason='변경된 사유' where leave_req_id=50");
            writer.createStatement().executeUpdate("update leave_request_date set requested_minutes=90 where request_date_id=504");
            writer.commit();
            JsonNode original = pending.get(10, TimeUnit.SECONDS).get("data");
            assertThat(original.get("status").asString()).isEqualTo("CANCELLED");
            assertThat(original.get("reason").asString()).isEqualTo("  개인 일정\n보존  ");
            assertThat(original.get("dates").get(2).get("requestedMinutes").asInt()).isEqualTo(60);
            JsonNode updated = response(ADMIN_URL + "/50").get("data");
            assertThat(updated.get("status").asString()).isEqualTo("APPROVED");
            assertThat(updated.get("reason").asString()).isEqualTo("변경된 사유");
            assertThat(updated.get("dates").get(2).get("requestedMinutes").asInt()).isEqualTo(90);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/50", "/0", "/abc"})
    void adminSecurityDeniedBeforeQueryOrRequestIdValidation(String endpoint) throws Exception {
        mvc.perform(get(ADMIN_URL + endpoint).header("X-Admin", "true").header("X-Employee-Id", "1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @Test
    void submittingIsVisibleInUserAndAdminListsAndDetailsWithoutNewJsonFields() throws Exception {
        jdbcTemplate.update("""
                update leave_request set status='SUBMITTING',submission_key='test-submitting',
                    submission_payload_hash=repeat('a',64),approval_creation_status='UNKNOWN',
                    approval_request_id='00000000-0000-0000-0000-000000000050',
                    approval_status_changed_at=now(),approval_attempt_count=1,approval_doc_id=null
                where leave_req_id=50
                """);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        for (String endpoint : List.of(URL, ADMIN_URL)) {
            JsonNode list = response(endpoint).get("data");
            boolean found = false;
            for (JsonNode request : list) {
                if (request.get("requestId").asLong() == 50) {
                    found = true;
                    assertThat(request.get("status").asString()).isEqualTo("SUBMITTING");
                    assertThat(request.size()).isEqualTo(endpoint.equals(URL) ? 10 : 11);
                }
            }
            assertThat(found).isTrue();
            JsonNode detail = response(endpoint + "/50").get("data");
            assertThat(detail.get("status").asString()).isEqualTo("SUBMITTING");
            assertThat(detail.size()).isEqualTo(17);
            assertThat(detail.has("submissionKey")).isFalse();
        }
    }

    private void assertInstant(JsonNode record, String field, String expected) {
        assertThat(OffsetDateTime.parse(record.get(field).asString()).toInstant()).isEqualTo(OffsetDateTime.parse(expected).toInstant());
    }

    @Test
    void listConcurrentCreationAppearsOnlyInNextStatementSnapshot() throws Exception {
        createGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = writerHoldingGate()) {
            Future<JsonNode> pending = executor.submit(() -> gatedResponse(URL));
            awaitGateWait();
            writer.createStatement().executeUpdate("""
                    insert into leave_request (leave_req_id,policy_version_id,leave_type_id,emp_id,status,created_at)
                    values (70,11,1,42,'SUBMITTED','2098-01-01Z')
                    """);
            writer.commit();
            assertThat(ids(pending.get(10, TimeUnit.SECONDS).get("data"), "requestId")).containsExactly(60L, 50L, 40L, 30L, 20L, 10L);
            assertThat(ids(response(URL).get("data"), "requestId")).containsExactly(70L, 60L, 50L, 40L, 30L, 20L, 10L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void detailConcurrentHeaderAndDateChangesCannotMixSnapshots() throws Exception {
        createGate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = writerHoldingGate()) {
            Future<JsonNode> pending = executor.submit(() -> gatedResponse(URL + "/50"));
            awaitGateWait();
            writer.createStatement().executeUpdate("update leave_request set status='APPROVED',reason='변경된 사유' where leave_req_id=50");
            writer.createStatement().executeUpdate("update leave_request_date set requested_minutes=90 where request_date_id=504");
            writer.commit();
            JsonNode original = pending.get(10, TimeUnit.SECONDS).get("data");
            assertThat(original.get("status").asString()).isEqualTo("CANCELLED");
            assertThat(original.get("reason").asString()).isEqualTo("  개인 일정\n보존  ");
            assertThat(original.get("dates").get(2).get("requestedMinutes").asInt()).isEqualTo(60);
            JsonNode updated = response(URL + "/50").get("data");
            assertThat(updated.get("status").asString()).isEqualTo("APPROVED");
            assertThat(updated.get("reason").asString()).isEqualTo("변경된 사유");
            assertThat(updated.get("dates").get(2).get("requestedMinutes").asInt()).isEqualTo(90);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/50"})
    void securityUnauthenticatedRequestsDoNotReadDatabase(String endpoint) throws Exception {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.UNAUTHORIZED));
        mvc.perform(get(URL + endpoint)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update leave_request set status='CANCEL_REQUESTED' where leave_req_id=50",
            "update leave_request set policy_version_id=99999 where leave_req_id=50",
            "update leave_request set leave_type_id=99999 where leave_req_id=50",
            "update leave_request set approval_doc_id=90050 where leave_req_id=20",
            "update leave_request set total_minutes=-1 where leave_req_id=50",
            "update leave_request set total_deduct_minutes=-1 where leave_req_id=50",
            "update leave_request_date set leave_req_id=99999 where request_date_id=501",
            "update leave_request_date set portion='HALF' where request_date_id=501",
            "update leave_request_date set requested_minutes=0 where request_date_id=501",
            "update leave_request_date set deduct_minutes=-1 where request_date_id=501",
            "update leave_request_date set scheduled_work_minutes=-1 where request_date_id=501",
            "update leave_request_date set end_at=start_at where request_date_id=504"
    })
    void schemaActualForeignKeysUniqueAndChecksRejectInvalidRecords(String sql) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaUsesDatesAndDirectApprovalIdWithoutAssumedTables() {
        assertThat(jdbcTemplate.queryForObject("select to_regclass('leave_request_item')::text", String.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select to_regclass('leave_approval_link')::text", String.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_request where approval_doc_id is null", Integer.class)).isEqualTo(6);
    }

    private void createGate() {
        jdbcTemplate.execute("""
                create or replace function request_query_test_gate() returns boolean language plpgsql volatile as $$
                begin perform pg_advisory_xact_lock(94425421); return true; end $$
                """);
    }

    private Connection writerHoldingGate() throws Exception {
        Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try {
            connection.setAutoCommit(false);
            connection.createStatement().execute("select pg_advisory_xact_lock(94425421)");
            return connection;
        } catch (Exception exception) {
            connection.close();
            throw exception;
        }
    }

    private JsonNode gatedResponse(String url) throws Exception {
        SqlCapture.gateQuery.set(true);
        try {
            return response(url);
        } finally {
            SqlCapture.gateQuery.remove();
        }
    }

    private void awaitGateWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbcTemplate.execute("select pg_stat_clear_snapshot()");
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity where wait_event_type='Lock' and wait_event='advisory'
                        and query like '%request_query_test_gate()%' and pid<>pg_backend_pid()
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        fail("신청 SELECT가 문장 스냅샷 생성 후 테스트 게이트에서 대기해야 한다.");
    }

    private JsonNode response(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private List<Long> ids(JsonNode records, String field) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode record : records) {
            ids.add(record.get(field).asLong());
        }
        return ids;
    }

    private void assertSingleRead() {
        assertThat(SqlCapture.statements.get()).singleElement().satisfies(sql ->
                assertThat(sql).startsWith("select ").doesNotContain("for update", "for share", "pg_advisory"));
        assertThat(SqlCapture.readOnlyFlags.get()).containsExactly(true);
        assertThat(SqlCapture.isolationLevels.get()).containsExactly((Integer) null);
    }

    private Map<String, List<Map<String, Object>>> storedRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }
}
