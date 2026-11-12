package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.client.attendance.HolidayClient;
import com.ssafy.leaveservice.client.attendance.HolidayClient.*;
import com.ssafy.leaveservice.domain.request.dto.*;
import com.ssafy.leaveservice.domain.request.entity.*;
import com.ssafy.leaveservice.domain.request.service.*;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ssafy.leaveservice.client.approval.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "leave.test.database-owner=LeaveSubmissionOrchestrationPostgresIntegrationTest",
        "leave.approval.enabled=true","spring.cloud.config.enabled=false","eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/","spring.jpa.open-in-view=false"})
@ActiveProfiles({"dev","test"})
@Import({PostgresTestConfiguration.class,
        com.ssafy.leaveservice.client.approval.FakeApprovalClientConfiguration.class})
class LeaveSubmissionOrchestrationPostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired LeaveSubmissionService service;
    @Autowired LeaveSubmissionOrchestrationService orchestration;
    @Autowired LeaveApprovalResultService approval;
    @Autowired MyLeaveRequestService myRequests;
    @Autowired com.ssafy.leaveservice.client.approval.FakeApprovalClientConfiguration.FakeApprovalClient client;
    @Autowired LeaveSubmissionTransactionService transactionService;
    @Autowired SubmissionPayloadHash hash;
    @Autowired MyLeaveBalanceService balances;
    @Autowired jakarta.validation.Validator validator;
    @Autowired WebApplicationContext context;
    @MockitoBean EmployeeAccess access;
    @MockitoBean HolidayClient holidays;
    private final ThreadLocal<Long> employee=ThreadLocal.withInitial(()->1L);
    private LocalDate day;
    private static final List<String> TABLES=List.of("leave_ledger","leave_request_allocation","leave_request_date","leave_request",
            "leave_grant","leave_policy_type_rule","employee_leave_policy_assignment","leave_account","leave_type",
            "leave_balance_type","leave_policy_version","leave_policy_group");

    @BeforeEach
    void setUp() {
        client.reset();
        employee.remove();
        when(access.currentEmployeeId()).thenAnswer(invocation->employee.get());
        when(holidays.findHolidays(any(),any())).thenAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new HolidayResult(true,List.of());
        });
        jdbc.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : TABLES) {
            jdbc.update("delete from " + table);
        }
        day=jdbc.queryForObject("select (clock_timestamp() at time zone 'Asia/Seoul')::date",LocalDate.class)
                .plusDays(30).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        jdbc.update("insert into leave_policy_group(policy_id,name) values(1,'정책'),(2,'다음 정책')");
        jdbc.update("""
                insert into leave_policy_version(policy_version_id,policy_id,version_no,base_type,unit_minutes)
                values(11,1,1,'HIRE_DATE',30),(12,1,2,'HIRE_DATE',30),(21,2,1,'HIRE_DATE',30)
                """);
        jdbc.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbc.update("update leave_policy_group set active_policy_version_id=21 where policy_id=2");
        jdbc.update("""
                insert into employee_leave_policy_assignment(assignment_id,emp_id,policy_id,effective_from)
                values(1,1,1,'2000-01-01Z'),(2,2,1,'2000-01-01Z')
                """);
        jdbc.update("insert into leave_balance_type(balance_type_id,name) values(1,'연차')");
        jdbc.update("""
                insert into leave_type(leave_type_id,balance_type_id,name,balance_mode,request_unit)
                values(1,1,'연차','ACCOUNT','MIXED'),(2,null,'비차감','NONE','MIXED')
                """);
        jdbc.update("""
                insert into leave_policy_type_rule(rule_id,policy_version_id,leave_type_id,allowed_units)
                values(1,11,1,'MIXED'),(2,11,2,'MIXED'),(3,12,1,'MIXED'),(4,21,1,'MIXED')
                """);
        jdbc.update("insert into leave_account(account_id,emp_id,balance_type_id) values(1,1,1),(2,2,1)");
        jdbc.update("""
                insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from)
                values(100,1,'MANUAL',99999,'2000-01-01Z'),(200,2,'MANUAL',99999,'2000-01-01Z')
                """);
        jdbc.update("""
                insert into leave_ledger(entry_id,account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                values(100,1,100,'GRANT',1440,'own','2000-01-01Z'),(200,2,200,'GRANT',1440,'other','2000-01-01Z')
                """);
        jdbc.execute("select setval(pg_get_serial_sequence('leave_ledger','entry_id'),1000)");
    }

    @Test
    void httpSuccessCommitsT1BeforeClientThenLinksT2AndKeepsReservation() throws Exception {
        client.behavior = command -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select pg_try_advisory_xact_lock(-1::bigint)",Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("select approval_creation_status from leave_request where leave_req_id=?",String.class,command.requestId())).isEqualTo("IN_PROGRESS");
            assertThat(jdbc.queryForObject("select count(*) from leave_request_allocation where leave_req_id=?",Integer.class,command.requestId())).isEqualTo(1);
            assertThat(balances.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
            assertThat(command.employeeId()).isEqualTo(1);
            assertThat(command.policyVersionId()).isEqualTo(11);
            assertThat(command.dates()).hasSize(1);
            assertThat(command.dates().getFirst().requestedMinutes()).isEqualTo(480);
            return result(command,ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,901L);
        };
        postRequest("key",body(1,null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.approvalCreationStatus").value("LINKED"))
                .andExpect(jsonPath("$.data.approvalDocId").value(901))
                .andExpect(jsonPath("$.data.employeeId").doesNotExist());
        assertThat(client.calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type='CONSUME'",Integer.class)).isZero();
        assertThat(balances.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
        assertThat(myRequests.getMyRequests().getFirst().status()).isEqualTo(LeaveRequestStatus.SUBMITTED);
        assertThat(myRequests.getMyRequest(myRequests.getMyRequests().getFirst().requestId()).approvalDocId()).isEqualTo(901);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success",Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("show server_version",String.class)).startsWith("17.");
    }

    @Test
    void noneRequestSubmitsWithoutReservationRows() throws Exception {
        postRequest("none",body(2,null)).andExpect(status().isCreated());
        assertThat(count("leave_request_allocation")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type='RESERVE'",Integer.class)).isZero();
        assertThat(client.commands.getFirst().totalDeductMinutes()).isZero();
    }

    @Test
    void confirmedAbsenceCancelsAndReleasesThenSameKeyReturnsSameFailedRequest() throws Exception {
        client.behavior = command -> result(command,ApprovalCreationResult.Outcome.NOT_CREATED_CONFIRMED,null);
        postRequest("key",body(1,null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.approvalCreationStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.approvalDocId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.message").value("결재 문서 미생성이 확인되어 신청이 취소되었습니다."));
        assertThat(balances.getMyBalances().getFirst().reservedMinutes()).isZero();
        assertThat(balances.getMyBalances().getFirst().remainingMinutes()).isEqualTo(1440);
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type='RELEASE' and quantity_delta=0 and reserved_delta=-480",Integer.class)).isEqualTo(1);
        Map<String,List<Map<String,Object>>> before = rows();
        postRequest("key",body(1,null)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(rows()).isEqualTo(before);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings={"TIMEOUT","CONNECTION_CLOSED","RESPONSE_LOST","HTTP_500","PARSE_ERROR"})
    void clientExceptionsRemainUnknownNeverReleaseAndNeverCreateAgain(String failure) throws Exception {
        client.behavior = command -> { throw new IllegalStateException(failure); };
        postRequest("key",body(1,null)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("SUBMITTING"))
                .andExpect(jsonPath("$.data.approvalCreationStatus").value("UNKNOWN"));
        UUID traceId = jdbc.queryForObject("select approval_request_id from leave_request",UUID.class);
        Map<String,List<Map<String,Object>>> before = rows();
        postRequest("key",body(1,null)).andExpect(status().isAccepted());
        assertThat(rows()).isEqualTo(before);
        assertThat(client.commands.getFirst().approvalRequestId()).isEqualTo(traceId);
        assertThat(client.calls.get()).isEqualTo(1);
        assertThat(client.lookups.get()).isZero();
        assertThat(balances.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
        postRequest("new-key",body(1,null)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_013"));
        assertThat(count("leave_request")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings={"NULL_RESULT","WRONG_TRACE","MISSING_DOCUMENT","ZERO_DOCUMENT","NEGATIVE_DOCUMENT","MISSING_OUTCOME","DRAFT_CREATED","CONTRADICTORY_FAILURE","UNKNOWN"})
    void incompleteOrUnconfirmedResultCannotBecomeSubmittedOrFailed(String kind) throws Exception {
        client.behavior = command -> switch (kind) {
            case "NULL_RESULT" -> null;
            case "WRONG_TRACE" -> new ApprovalCreationResult(UUID.randomUUID(),ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,901L);
            case "MISSING_DOCUMENT" -> result(command,ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,null);
            case "ZERO_DOCUMENT" -> result(command,ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,0L);
            case "NEGATIVE_DOCUMENT" -> result(command,ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,-1L);
            case "MISSING_OUTCOME" -> result(command,null,901L);
            case "DRAFT_CREATED" -> result(command,ApprovalCreationResult.Outcome.DRAFT_CREATED,901L);
            case "CONTRADICTORY_FAILURE" -> result(command,ApprovalCreationResult.Outcome.NOT_CREATED_CONFIRMED,901L);
            default -> result(command,ApprovalCreationResult.Outcome.UNKNOWN,null);
        };
        postRequest("key",body(1,null)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.approvalCreationStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.approvalDocId").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type='RELEASE'",Integer.class)).isZero();
        assertThat(balances.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
    }

    @Test
    void submittedReplayAndPayloadConflictNeverCallClientAgain() throws Exception {
        postRequest("key",body(1,null)).andExpect(status().isCreated());
        Map<String,List<Map<String,Object>>> before = rows();
        when(holidays.findHolidays(any(),any())).thenReturn(HolidayResult.unavailable());
        postRequest("key",body(1,null)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.approvalDocId").value(901));
        postRequest("key",body(1,"changed")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_014"));
        assertThat(rows()).isEqualTo(before);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyCallsClientOnceWhileSecondRequestSeesProcessing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        client.behavior = command -> {
            entered.countDown();
            try { assertThat(finish.await(15,TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            return result(command,ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,901L);
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(()->postRequest("key",body(1,null)).andReturn());
            assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
            postRequest("key",body(1,null)).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.approvalCreationStatus").value("IN_PROGRESS"));
            postRequest("key",body(1,"different")).andExpect(status().isConflict());
            assertThat(client.calls.get()).isEqualTo(1);
            finish.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(count("leave_request")).isEqualTo(1);
            assertThat(count("leave_request_date")).isEqualTo(1);
            assertThat(count("leave_request_allocation")).isEqualTo(1);
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void t1FailureDoesNotCallApproval() throws Exception {
        jdbc.update("update leave_type set is_active=false where leave_type_id=1");
        Map<String,List<Map<String,Object>>> before = rows();
        postRequest("key",body(1,null)).andExpect(status().isConflict());
        assertThat(rows()).isEqualTo(before);
        assertThat(client.calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"LINK","COMPENSATE"})
    void t2DatabaseFailurePreservesCommittedT1AndRollsBackAllT2Writes(String operation) throws Exception {
        if (operation.equals("COMPENSATE")) {
            jdbc.update("update leave_ledger set quantity_delta=240 where entry_id=100");
            jdbc.update("insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from) values(101,1,'MANUAL',960,'2000-01-01Z')");
            jdbc.update("insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at) values(1,101,'GRANT',960,'second','2000-01-01Z')");
            client.behavior = command -> result(command,ApprovalCreationResult.Outcome.NOT_CREATED_CONFIRMED,null);
        }
        String table = operation.equals("LINK") ? "leave_request" : "leave_ledger";
        String event = operation.equals("LINK") ? "update" : "insert";
        String condition = operation.equals("LINK") ? "new.approval_creation_status='LINKED'" : "new.event_type='RELEASE' and new.grant_id=101";
        jdbc.execute("create or replace function t3_fail() returns trigger language plpgsql as $$ begin if " + condition
                + " then raise exception 'T2 isolated failure' using errcode='23514'; end if; return new; end $$");
        jdbc.execute("create trigger t3_fail before " + event + " on " + table + " for each row execute function t3_fail()");
        try {
            postRequest("key",body(1,null)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
            assertThat(jdbc.queryForObject("select status from leave_request",String.class)).isEqualTo("SUBMITTING");
            assertThat(jdbc.queryForObject("select approval_creation_status from leave_request",String.class)).isEqualTo("IN_PROGRESS");
            assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type='RELEASE'",Integer.class)).isZero();
            assertThat(balances.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
            postRequest("key",body(1,null)).andExpect(status().isAccepted());
            assertThat(client.calls.get()).isEqualTo(1);
        } finally {
            jdbc.execute("drop trigger t3_fail on " + table);
        }
    }

    @Test
    void interruptedProcessMarkerSurvivesFreshOrchestratorWithoutAnotherCreate() throws Exception {
        client.behavior = command -> { throw new AssertionError("simulated process stop after marker commit"); };
        assertThatThrownBy(()->orchestration.submit("key",input(1,null,date(day,LeaveRequestPortion.FULL))))
                .isInstanceOf(AssertionError.class);
        assertThat(jdbc.queryForObject("select approval_creation_status from leave_request",String.class)).isEqualTo("IN_PROGRESS");
        LeaveSubmissionOrchestrationService fresh = context.getAutowireCapableBeanFactory().createBean(LeaveSubmissionOrchestrationService.class);
        LeaveSubmissionResult retry = fresh.submit("key",input(1,null,date(day,LeaveRequestPortion.FULL)));
        assertThat(retry.approvalCreationStatus()).isEqualTo(ApprovalCreationStatus.IN_PROGRESS);
        assertThat(client.calls.get()).isEqualTo(1);
        assertThat(balances.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
    }

    @Test
    void orchestrationRejectsOuterTransactionViaSpringProxy() {
        org.springframework.transaction.support.TransactionTemplate outer = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        assertThatThrownBy(()->outer.executeWithoutResult(status->orchestration.submit("key",input(1,null,date(day,LeaveRequestPortion.FULL)))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(count("leave_request")).isZero();
        assertThat(client.calls.get()).isZero();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(orchestration)).isTrue();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(transactionService)).isTrue();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(approval)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings={"", " ", "한글", "TOO_LONG"})
    void invalidKeysDoNotInsertOrCallClient(String key) throws Exception {
        if (key.equals("TOO_LONG")) {
            key = "a".repeat(129);
        }
        postRequest(key,body(1,null)).andExpect(status().isBadRequest());
        assertThat(count("leave_request")).isZero();
        assertThat(client.calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"{}", "{\"leaveTypeId\":1,\"dates\":[]}", "{\"leaveTypeId\":1,\"employeeId\":2,\"dates\":[]}"})
    void invalidBodiesAreRejectedBeforeT1(String json) throws Exception {
        postRequest("key",json).andExpect(status().isBadRequest());
        assertThat(count("leave_request")).isZero();
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void missingHeaderUsesExistingBadRequestEnvelope() throws Exception {
        mvc().perform(post("/api/v1/leaves/me/requests").contentType(MediaType.APPLICATION_JSON).content(body(1,null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(count("leave_request")).isZero();
    }

    @Test
    void unavailableAuthenticationStillReturns503WithoutAnyWrite() throws Exception {
        when(access.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));
        postRequest("key",body(1,null)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.AUTHENTICATION_UNAVAILABLE.getCode()));
        assertThat(count("leave_request")).isZero();
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void simultaneousNewKeyRequestsProduceOneClientCallAndOneReservation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<MvcResult> request = () -> {
                start.await();
                return postRequest("race",body(1,null)).andReturn();
            };
            Future<MvcResult> first = executor.submit(request);
            Future<MvcResult> second = executor.submit(request);
            start.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isIn(201,202);
            assertThat(second.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isIn(201,202);
            assertThat(client.calls.get()).isEqualTo(1);
            assertThat(count("leave_request")).isEqualTo(1);
            assertThat(count("leave_request_allocation")).isEqualTo(1);
            assertThat(balances.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void canonicalPayloadHashAcceptsReorderedDatesWithoutNewClientCall() throws Exception {
        String first = "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\""+day+"\",\"portion\":\"FULL\"},{\"leaveDate\":\""+day.plusDays(1)+"\",\"portion\":\"FULL\"}]}";
        String reordered = "{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\""+day.plusDays(1)+"\",\"portion\":\"FULL\"},{\"leaveDate\":\""+day+"\",\"portion\":\"FULL\"}]}";
        postRequest("ordered",first).andExpect(status().isCreated());
        Map<String,List<Map<String,Object>>> before = rows();
        postRequest("ordered",reordered).andExpect(status().isCreated());
        assertThat(rows()).isEqualTo(before);
        assertThat(client.calls.get()).isEqualTo(1);
        OffsetDateTime storedLeaveDate = client.commands.getFirst().dates().getFirst().leaveDate();
        assertThat(storedLeaveDate.atZoneSameInstant(PreviewGrantAllocator.SEOUL).toLocalDate()).isEqualTo(day);
        assertThat(storedLeaveDate.toInstant()).isEqualTo(day.atStartOfDay(PreviewGrantAllocator.SEOUL).toInstant());
    }
    private org.springframework.test.web.servlet.ResultActions postRequest(String key, String json) throws Exception {
        return mvc().perform(post("/api/v1/leaves/me/requests").header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String body(long typeId, String reason) {
        String reasonField = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return "{\"leaveTypeId\":" + typeId + reasonField + ",\"dates\":[{\"leaveDate\":\"" + day + "\",\"portion\":\"FULL\"}]}";
    }

    private ApprovalCreationResult result(ApprovalCreateCommand command, ApprovalCreationResult.Outcome outcome, Long documentId) {
        return new ApprovalCreationResult(command.approvalRequestId(),outcome,documentId);
    }
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private Map<String,List<Map<String,Object>>> rows() {
        Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
        for (String table : TABLES) {
            result.put(table, jdbc.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return result;
    }
    private void assertBusiness(Runnable action,ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));
    }
    private static LeaveRequestPreviewRequest input(long type,String reason,LeaveRequestPreviewRequest.DateRequest... dates) {
        return new LeaveRequestPreviewRequest(type,reason,List.of(dates));
    }
    private static LeaveRequestPreviewRequest.DateRequest date(LocalDate day,LeaveRequestPortion portion) {
        return new LeaveRequestPreviewRequest.DateRequest(day,portion);
    }
}
