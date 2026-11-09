package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.client.attendance.HolidayClient;
import com.ssafy.leaveservice.client.attendance.HolidayClient.*;
import com.ssafy.leaveservice.domain.request.dto.*;
import com.ssafy.leaveservice.domain.request.entity.*;
import com.ssafy.leaveservice.domain.request.service.*;
import com.ssafy.leaveservice.domain.account.service.MyLeaveBalanceService;
import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentRequest;
import com.ssafy.leaveservice.domain.adjustment.service.LeaveAdjustmentService;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "leave.test.database-owner=LeaveApprovalResultPostgresIntegrationTest",
        "spring.cloud.config.enabled=false","eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/","spring.jpa.open-in-view=false"})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeaveApprovalResultPostgresIntegrationTest {

    @Autowired
    private PostgreSQLContainer postgres;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired LeaveSubmissionService submissionService;
    @Autowired LeaveApprovalResultService approvalResultService;
    @Autowired LeaveApprovalSettlementService settlementService;
    @Autowired AdminLeaveCancellationTransactionService cancellationTransactions;
    @Autowired LeaveAdjustmentService adjustments;
    @Autowired MyLeaveRequestService myLeaveRequestService;
    @Autowired AdminLeaveRequestService adminLeaveRequestService;
    @Autowired com.ssafy.leaveservice.domain.ledger.service.MyLeaveLedgerService myLeaveLedgerService;
    @Autowired MyLeaveBalanceService balanceService;
    @Autowired WebApplicationContext context;
    @MockitoBean EmployeeAccess employeeAccess;
    @MockitoBean HolidayClient holidayClient;
    private final ThreadLocal<Long> currentEmployeeId=ThreadLocal.withInitial(()->1L);
    private LocalDate day;
    private static final List<String> TABLES=List.of("leave_ledger","leave_request_allocation","leave_request_date","leave_request",
            "leave_grant","leave_policy_type_rule","employee_leave_policy_assignment","leave_account","leave_type",
            "leave_balance_type","leave_policy_version","leave_policy_group");

    @BeforeEach
    void setUp() {
        currentEmployeeId.remove();
        when(employeeAccess.currentEmployeeId()).thenAnswer(invocation->currentEmployeeId.get());
        when(holidayClient.findHolidays(any(),any())).thenAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new HolidayResult(true,List.of());
        });
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : TABLES) {
            jdbcTemplate.update("delete from " + table);
        }
        day=jdbcTemplate.queryForObject("select (clock_timestamp() at time zone 'Asia/Seoul')::date",LocalDate.class)
                .plusDays(30).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        jdbcTemplate.update("insert into leave_policy_group(policy_id,name) values(1,'정책'),(2,'다음 정책')");
        jdbcTemplate.update("""
                insert into leave_policy_version(policy_version_id,policy_id,version_no,base_type,unit_minutes)
                values(11,1,1,'HIRE_DATE',30),(12,1,2,'HIRE_DATE',30),(21,2,1,'HIRE_DATE',30)
                """);
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=21 where policy_id=2");
        jdbcTemplate.update("""
                insert into employee_leave_policy_assignment(assignment_id,emp_id,policy_id,effective_from)
                values(1,1,1,'2000-01-01Z'),(2,2,1,'2000-01-01Z')
                """);
        jdbcTemplate.update("insert into leave_balance_type(balance_type_id,name) values(1,'연차')");
        jdbcTemplate.update("""
                insert into leave_type(leave_type_id,balance_type_id,name,balance_mode,request_unit)
                values(1,1,'연차','ACCOUNT','MIXED'),(2,null,'비차감','NONE','MIXED')
                """);
        jdbcTemplate.update("""
                insert into leave_policy_type_rule(rule_id,policy_version_id,leave_type_id,allowed_units)
                values(1,11,1,'MIXED'),(2,11,2,'MIXED'),(3,12,1,'MIXED'),(4,21,1,'MIXED')
                """);
        jdbcTemplate.update("insert into leave_account(account_id,emp_id,balance_type_id) values(1,1,1),(2,2,1)");
        jdbcTemplate.update("""
                insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from)
                values(100,1,'MANUAL',99999,'2000-01-01Z'),(200,2,'MANUAL',99999,'2000-01-01Z')
                """);
        jdbcTemplate.update("""
                insert into leave_ledger(entry_id,account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                values(100,1,100,'GRANT',1440,'own','2000-01-01Z'),(200,2,200,'GRANT',1440,'other','2000-01-01Z')
                """);
        jdbcTemplate.execute("select setval(pg_get_serial_sequence('leave_ledger','entry_id'),1000)");
    }

    @Test
    void firstCallIsCommittedOnceAndOnlyFirstCallerMaySend() {
        LeaveSubmissionResult initial = pending();
        Map<String, Object> original = requestRow(initial);
        LeaveApprovalCallStartResult started = approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        assertThat(started.callRequired()).isTrue();
        assertThat(started.request().approvalRequestId()).isEqualTo(initial.approvalRequestId());
        assertThat(started.request().approvalCreationStatus()).isEqualTo(ApprovalCreationStatus.IN_PROGRESS);
        assertThat(requestRow(initial).get("approval_attempt_count")).isEqualTo(1);
        assertThat(requestRow(initial).get("approval_status_changed_at")).isNotEqualTo(original.get("approval_status_changed_at"));
        Map<String,List<Map<String,Object>>> before = rows();
        assertThat(approvalResultService.startCall(initial.requestId(),initial.approvalRequestId()).callRequired()).isFalse();
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void successLinksActualDocumentWithoutAnyLedgerWriteAndReplaysDoNotUpdateXmin() {
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(),initial.approvalRequestId());
        List<Map<String,Object>> ledgerBefore = jdbcTemplate.queryForList("select *,xmin::text as xmin from leave_ledger order by entry_id");
        LeaveSubmissionResult linked = approvalResultService.linkCreatedDocument(initial.requestId(),initial.approvalRequestId(),901L);
        assertThat(linked.status()).isEqualTo(LeaveRequestStatus.SUBMITTED);
        assertThat(linked.approvalCreationStatus()).isEqualTo(ApprovalCreationStatus.LINKED);
        assertThat(linked.approvalDocId()).isEqualTo(901L);
        assertThat(requestRow(initial).get("submitted_at")).isNotNull();
        assertThat(requestRow(initial).get("approval_last_error")).isNull();
        assertThat(balanceService.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
        assertThat(jdbcTemplate.queryForList("select *,xmin::text as xmin from leave_ledger order by entry_id")).isEqualTo(ledgerBefore);
        Map<String,List<Map<String,Object>>> before = rows();
        assertThat(approvalResultService.linkCreatedDocument(initial.requestId(),initial.approvalRequestId(),901L)).isEqualTo(linked);
        assertThat(approvalResultService.startCall(initial.requestId(),initial.approvalRequestId()).callRequired()).isFalse();
        assertThat(rows()).isEqualTo(before);
        assertBusiness(()->approvalResultService.linkCreatedDocument(initial.requestId(),initial.approvalRequestId(),902L),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertBusiness(()->approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED"),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void sameDocumentCannotBeConnectedToAnotherRequest() {
        LeaveSubmissionResult first = pending();
        LeaveSubmissionResult second = submissionService.submit("second",input(1,null,date(day.plusDays(1),LeaveRequestPortion.FULL)));
        approvalResultService.linkCreatedDocument(first.requestId(),first.approvalRequestId(),901L);
        Map<String,List<Map<String,Object>>> before = rows();
        assertBusiness(()->approvalResultService.linkCreatedDocument(second.requestId(),second.approvalRequestId(),901L),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void confirmedFailureReleasesEveryOriginalAllocationAndReplaysPreserveAllRows() {
        splitGrants();
        LeaveSubmissionResult initial = submissionService.submit("key",input(1,"원본",date(day,LeaveRequestPortion.FULL),date(day.plusDays(1),LeaveRequestPortion.AM_HALF)));
        assertThat(count("leave_request_allocation")).isEqualTo(3);
        List<Map<String,Object>> datesBefore = jdbcTemplate.queryForList("select *,xmin::text as xmin from leave_request_date order by request_date_id");
        List<Map<String,Object>> allocationsBefore = jdbcTemplate.queryForList("select *,xmin::text as xmin from leave_request_allocation order by allocation_id");
        LeaveSubmissionResult cancelled = approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"REMOTE_CONFIRMED_ABSENT");
        assertThat(cancelled.status()).isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThat(cancelled.approvalCreationStatus()).isEqualTo(ApprovalCreationStatus.FAILED);
        assertThat(cancelled.approvalDocId()).isNull();
        assertThat(requestRow(initial).get("cancelled_at")).isNotNull();
        assertThat(requestRow(initial).get("cancelled_by")).isEqualTo(1L);
        assertThat(requestRow(initial).get("approval_last_error")).isEqualTo("REMOTE_CONFIRMED_ABSENT");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from leave_ledger release join leave_request_allocation allocation
                on allocation.allocation_id=release.allocation_id
                where release.event_type='RELEASE' and release.quantity_delta=0
                  and release.reserved_delta=-allocation.allocated_minutes
                  and release.account_id=allocation.account_id and release.grant_id=allocation.grant_id
                  and release.leave_req_id=allocation.leave_req_id
                """,Integer.class)).isEqualTo(3);
        assertThat(balanceService.getMyBalances().getFirst().reservedMinutes()).isZero();
        assertThat(balanceService.getMyBalances().getFirst().remainingMinutes()).isEqualTo(1200);
        assertThat(jdbcTemplate.queryForList("select *,xmin::text as xmin from leave_request_date order by request_date_id")).isEqualTo(datesBefore);
        assertThat(jdbcTemplate.queryForList("select *,xmin::text as xmin from leave_request_allocation order by allocation_id")).isEqualTo(allocationsBefore);
        Map<String,List<Map<String,Object>>> before = rows();
        assertThat(approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"SECOND_CONFIRMATION")).isEqualTo(cancelled);
        assertThat(approvalResultService.startCall(initial.requestId(),initial.approvalRequestId()).callRequired()).isFalse();
        assertThat(rows()).isEqualTo(before);
        assertBusiness(()->approvalResultService.linkCreatedDocument(initial.requestId(),initial.approvalRequestId(),901L),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void nonDeductingRequestClosesWithoutLedgerOrAllocations() {
        LeaveSubmissionResult initial = submissionService.submit("none",input(2,null,date(day,LeaveRequestPortion.FULL)));
        int entries = count("leave_ledger");
        approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED");
        assertThat(count("leave_request_allocation")).isZero();
        assertThat(count("leave_ledger")).isEqualTo(entries);
        assertThat(requestRow(initial).get("status")).isEqualTo("CANCELLED");
    }

    @ParameterizedTest
    @ValueSource(strings={"TIMEOUT","HTTP_500","CONNECTION_CLOSED","RESPONSE_LOST","INVALID_RESPONSE"})
    void uncertainResultsKeepUuidRequestAndReservationAndDoNotRestart(String reason) {
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(),initial.approvalRequestId());
        LeaveSubmissionResult unknown = approvalResultService.recordUnknown(initial.requestId(),initial.approvalRequestId(),reason);
        assertThat(unknown.status()).isEqualTo(LeaveRequestStatus.SUBMITTING);
        assertThat(unknown.approvalCreationStatus()).isEqualTo(ApprovalCreationStatus.UNKNOWN);
        assertThat(unknown.approvalRequestId()).isEqualTo(initial.approvalRequestId());
        assertThat(unknown.approvalDocId()).isNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_ledger where event_type='RELEASE'",Integer.class)).isZero();
        assertThat(balanceService.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
        Map<String,List<Map<String,Object>>> before = rows();
        assertThat(approvalResultService.startCall(initial.requestId(),initial.approvalRequestId()).callRequired()).isFalse();
        assertThat(approvalResultService.recordUnknown(initial.requestId(),initial.approvalRequestId(),reason)).isEqualTo(unknown);
        assertThat(submissionService.submit("key",input(1,null,date(day,LeaveRequestPortion.FULL)))).isEqualTo(unknown);
        assertThat(rows()).isEqualTo(before);
        assertBusiness(()->submissionService.submit("bypass",input(1,null,date(day,LeaveRequestPortion.FULL))),ErrorCode.LEAVE_REQUEST_NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void unknownMayResolveOnlyToConfirmedOutcome(boolean success) {
        LeaveSubmissionResult initial = pending();
        approvalResultService.recordUnknown(initial.requestId(),initial.approvalRequestId(),"TIMEOUT");
        LeaveSubmissionResult resolved = success
                ? approvalResultService.linkCreatedDocument(initial.requestId(),initial.approvalRequestId(),901L)
                : approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"CONFIRMED_ABSENT");
        assertThat(resolved.status()).isEqualTo(success ? LeaveRequestStatus.SUBMITTED : LeaveRequestStatus.CANCELLED);
        assertThat(resolved.approvalRequestId()).isEqualTo(initial.approvalRequestId());
    }

    @ParameterizedTest
    @ValueSource(strings={"SUBMITTED","APPROVED","REJECTED","WITHDRAWN","CANCELLED"})
    void linkedTerminalStatesRejectUnknownAndCompensationWithoutDowngrade(String state) {
        LeaveSubmissionResult initial = pending();
        approvalResultService.linkCreatedDocument(initial.requestId(),initial.approvalRequestId(),901L);
        jdbcTemplate.update("update leave_request set status=? where leave_req_id=?",state,initial.requestId());
        Map<String,List<Map<String,Object>>> before = rows();
        assertBusiness(()->approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED"),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertBusiness(()->approvalResultService.recordUnknown(initial.requestId(),initial.approvalRequestId(),"TIMEOUT"),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(approvalResultService.linkCreatedDocument(initial.requestId(),initial.approvalRequestId(),901L).status()).isEqualTo(LeaveRequestStatus.valueOf(state));
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void traceUuidMismatchForEveryOperationLeavesAllRowsUntouched() {
        LeaveSubmissionResult initial = pending();
        UUID wrong = UUID.randomUUID();
        Map<String,List<Map<String,Object>>> before = rows();
        assertBusiness(()->approvalResultService.startCall(initial.requestId(),wrong),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertBusiness(()->approvalResultService.linkCreatedDocument(initial.requestId(),wrong,901L),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertBusiness(()->approvalResultService.recordUnknown(initial.requestId(),wrong,"TIMEOUT"),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertBusiness(()->approvalResultService.confirmNotCreated(initial.requestId(),wrong,"NOT_CREATED"),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void otherEmployeeAndMissingRequestAreIndistinguishableAndAdminDoesNotBypassOwnership() {
        LeaveSubmissionResult initial = pending();
        currentEmployeeId.set(2L);
        when(employeeAccess.isAdmin(2L)).thenReturn(true);
        Map<String,List<Map<String,Object>>> before = rows();
        assertBusiness(()->approvalResultService.linkCreatedDocument(initial.requestId(),initial.approvalRequestId(),901L),ErrorCode.NOT_FOUND);
        assertBusiness(()->approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED"),ErrorCode.NOT_FOUND);
        assertBusiness(()->approvalResultService.startCall(Long.MAX_VALUE,UUID.randomUUID()),ErrorCode.NOT_FOUND);
        assertThat(rows()).isEqualTo(before);
        verify(employeeAccess,never()).isAdmin(any());
    }

    @ParameterizedTest
    @ValueSource(strings={"MISSING_RESERVE","WRONG_AMOUNT","PREVIOUS_RELEASE","PREVIOUS_CONSUME","EXTRA_RESERVE","WRONG_ACCOUNT","WRONG_GRANT","NULL_ALLOCATION","BAD_GRANT_RESERVATION"})
    void inconsistentReservationsAreNeverSilentlyRepaired(String corruption) {
        LeaveSubmissionResult initial = pending();
        switch (corruption) {
            case "MISSING_RESERVE" -> jdbcTemplate.update("delete from leave_ledger where event_type='RESERVE'");
            case "WRONG_AMOUNT" -> jdbcTemplate.update("update leave_ledger set reserved_delta=240 where event_type='RESERVE'");
            case "WRONG_ACCOUNT" -> {
                jdbcTemplate.update("delete from leave_ledger where event_type='RESERVE'");
                jdbcTemplate.update("insert into leave_ledger(account_id,grant_id,leave_req_id,event_type,reserved_delta,idempotency_key) values(2,200,?,'RESERVE',480,'wrong-owner')",initial.requestId());
            }
            case "WRONG_GRANT" -> {
                jdbcTemplate.update("""
                        insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from)
                        values(101,1,'MANUAL',480,'2000-01-01Z')
                        """);
                jdbcTemplate.update("update leave_ledger set allocation_id=null,grant_id=101 where event_type='RESERVE'");
            }
            case "NULL_ALLOCATION" -> jdbcTemplate.update("update leave_ledger set allocation_id=null where event_type='RESERVE'");
            case "BAD_GRANT_RESERVATION" -> jdbcTemplate.update("update leave_ledger set quantity_delta=200 where event_type='GRANT' and account_id=1");
            default -> {
                String event = corruption.equals("PREVIOUS_RELEASE") ? "RELEASE"
                        : corruption.equals("PREVIOUS_CONSUME") ? "CONSUME" : "RESERVE";
                jdbcTemplate.update("""
                        insert into leave_ledger(account_id,grant_id,leave_req_id,allocation_id,event_type,
                            quantity_delta,reserved_delta,idempotency_key)
                        select account_id,grant_id,leave_req_id,allocation_id,?, ?,?,'other_path'
                        from leave_request_allocation where leave_req_id=?
                        """,event,event.equals("CONSUME") ? -480 : 0,event.equals("RESERVE") ? 480 : -480,initial.requestId());
            }
        }
        Map<String,List<Map<String,Object>>> before = rows();
        assertBusiness(()->approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED"),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void expiresAfterT1StillReleasesOriginalGrantWithoutFefoOrPolicyRevalidation() {
        LeaveSubmissionResult initial = pending();
        jdbcTemplate.update("update leave_grant set expires_on=clock_timestamp()-interval '1 second' where grant_id=100");
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=12 where policy_id=1");
        jdbcTemplate.update("update leave_type set is_active=false where leave_type_id=1");
        approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED");
        assertThat(jdbcTemplate.queryForObject("select sum(reserved_delta) from leave_ledger where grant_id=100",Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from leave_ledger where event_type='RELEASE' and grant_id=100",Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings={"SECOND_RELEASE","REQUEST_UPDATE","RELEASE_KEY_CONFLICT"})
    void anyIntermediateFailureRollsBackAllReleasesAndRequestState(String failure) {
        splitGrants();
        LeaveSubmissionResult initial = pending();
        String table = failure.equals("REQUEST_UPDATE") ? "leave_request" : "leave_ledger";
        String operation = failure.equals("REQUEST_UPDATE") ? "update" : "insert";
        String condition = failure.equals("REQUEST_UPDATE") ? "new.approval_creation_status='FAILED'"
                : "new.event_type='RELEASE' and new.grant_id=101";
        String body = failure.equals("RELEASE_KEY_CONFLICT") ? "new.idempotency_key='own'; return new;"
                : "raise exception 'T2 rollback proof';";
        jdbcTemplate.execute("create or replace function t2_test_fail() returns trigger language plpgsql as $$ begin if "
                + condition + " then " + body + " end if; return new; end $$");
        jdbcTemplate.execute("create trigger t2_fail before " + operation + " on " + table + " for each row execute function t2_test_fail()");
        Map<String,List<Map<String,Object>>> before = rows();
        try {
            assertThatThrownBy(()->approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(rows()).isEqualTo(before);
            assertThat(balanceService.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
        } finally {
            jdbcTemplate.execute("drop trigger t2_fail on " + table);
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"SUCCESS_SUCCESS","FAILURE_FAILURE","SUCCESS_FAILURE","START_START"})
    void concurrentOutcomesSerializeAndNeverDuplicateLedger(String scenario) throws Exception {
        LeaveSubmissionResult initial = pending();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> first = executor.submit(()->outcome(start,initial,scenario.equals("FAILURE_FAILURE") ? "FAILURE" : scenario.equals("START_START") ? "START" : "SUCCESS"));
            Future<Object> second = executor.submit(()->outcome(start,initial,scenario.equals("SUCCESS_SUCCESS") ? "SUCCESS" : scenario.equals("START_START") ? "START" : "FAILURE"));
            start.countDown();
            List<Object> outcomes = List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            if (scenario.equals("SUCCESS_FAILURE")) {
                assertThat(outcomes.stream().filter(value->value instanceof BusinessException).count()).isEqualTo(1);
            } else {
                assertThat(outcomes).noneMatch(value->value instanceof RuntimeException);
            }
            int releases = jdbcTemplate.queryForObject("select count(*) from leave_ledger where event_type='RELEASE'",Integer.class);
            assertThat(releases).isLessThanOrEqualTo(1);
            assertThat(balanceService.getMyBalances().getFirst().reservedMinutes()).isEqualTo(releases == 1 ? 0 : 480);
            if (scenario.equals("START_START")) {
                assertThat(outcomes.stream().map(value->(LeaveApprovalCallStartResult)value).filter(LeaveApprovalCallStartResult::callRequired).count()).isEqualTo(1);
                assertThat(requestRow(initial).get("approval_attempt_count")).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void afterRealEmployeeLockWaitT2ReadsCommittedOutcomeAndConflicts() throws Exception {
        LeaveSubmissionResult initial = pending();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = writer()) {
            writer.createStatement().execute("select pg_advisory_xact_lock(-1::bigint)");
            Future<?> waiting = executor.submit(()->approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED"));
            awaitWait("advisory");
            writer.createStatement().executeUpdate("update leave_request set status='SUBMITTED',approval_creation_status='LINKED',approval_doc_id=901,submitted_at=clock_timestamp() where leave_req_id=" + initial.requestId());
            writer.commit();
            assertThatThrownBy(()->waiting.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class);
            assertThat(jdbcTemplate.queryForObject("select count(*) from leave_ledger where event_type='RELEASE'",Integer.class)).isZero();
            assertThat(requestRow(initial).get("status")).isEqualTo("SUBMITTED");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void t1ReplayAndExistingGetApisReflectT2WithoutChangingTheirContract(boolean success) throws Exception {
        LeaveSubmissionResult initial = pending();
        LeaveSubmissionResult result = success ? approvalResultService.linkCreatedDocument(initial.requestId(),initial.approvalRequestId(),901L)
                : approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED");
        assertThat(submissionService.submit("key",input(1,null,date(day,LeaveRequestPortion.FULL)))).isEqualTo(result);
        assertBusiness(()->submissionService.submit("key",input(1,"changed",date(day,LeaveRequestPortion.FULL))),ErrorCode.LEAVE_SUBMISSION_KEY_CONFLICT);
        when(employeeAccess.isAdmin(1L)).thenReturn(true);
        assertThat(myLeaveRequestService.getMyRequests().getFirst().status()).isEqualTo(result.status());
        assertThat(myLeaveRequestService.getMyRequest(initial.requestId()).approvalDocId()).isEqualTo(result.approvalDocId());
        assertThat(adminLeaveRequestService.getAllRequests().getFirst().status()).isEqualTo(result.status());
        assertThat(adminLeaveRequestService.getRequest(initial.requestId().toString()).approvalDocId()).isEqualTo(result.approvalDocId());
        assertThat(myLeaveLedgerService.getMyLedger().stream().filter(entry->entry.eventType().name().equals("RELEASE")).count()).isEqualTo(success ? 0 : 1);
        org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        for (String path : List.of("/api/v1/leaves/me/requests/","/api/v1/leaves/admin/requests/")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+initial.requestId()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.status").value(result.status().name()))
                    .andExpect(jsonPath("$.data.approvalCreationStatus").doesNotExist());
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from flyway_schema_history where success",Integer.class)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("show server_version",String.class)).startsWith("17.");
    }

    @Test
    void newKeyAfterConfirmedCancellationCreatesNewRequestWhileOriginalIdentityRemains() {
        LeaveSubmissionResult initial = pending();
        approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED");
        LeaveSubmissionResult next = submissionService.submit("new-key",input(1,null,date(day,LeaveRequestPortion.FULL)));
        assertThat(next.requestId()).isNotEqualTo(initial.requestId());
        assertThat(next.approvalRequestId()).isNotEqualTo(initial.approvalRequestId());
        assertThat(count("leave_request")).isEqualTo(2);
        assertThat(balanceService.getMyBalances().getFirst().reservedMinutes()).isEqualTo(480);
    }

    @Test
    void preloadedPersistenceContextIsRefreshedAfterEmployeeLock() {
        LeaveSubmissionResult initial = pending();
        jakarta.persistence.EntityManagerFactory factory = context.getBean(jakarta.persistence.EntityManagerFactory.class);
        jakarta.persistence.EntityManager manager = factory.createEntityManager();
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(factory,
                new org.springframework.orm.jpa.EntityManagerHolder(manager));
        try {
            LeaveRequest stale = manager.find(LeaveRequest.class,initial.requestId());
            assertThat(stale.getStatus()).isEqualTo(LeaveRequestStatus.SUBMITTING);
            jdbcTemplate.update("update leave_request set status='SUBMITTED',approval_creation_status='LINKED',approval_doc_id=901 where leave_req_id=?",initial.requestId());
            assertBusiness(()->approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED"),ErrorCode.DATA_INTEGRITY_VIOLATION);
            assertThat(jdbcTemplate.queryForObject("select count(*) from leave_ledger where event_type='RELEASE'",Integer.class)).isZero();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(factory);
            manager.close();
        }
    }

    @Test
    void independentT2TransactionUsesReadCommittedAndSurvivesCallerRollback() {
        LeaveSubmissionResult initial = pending();
        jdbcTemplate.execute("""
                create or replace function t2_check_isolation() returns trigger language plpgsql as $$
                begin if current_setting('transaction_isolation') <> 'read committed' then
                raise exception 'Wrong isolation'; end if; return new; end $$
                """);
        jdbcTemplate.execute("create trigger t2_isolation before update on leave_request for each row execute function t2_check_isolation()");
        try {
            org.springframework.transaction.support.TransactionTemplate outer = new org.springframework.transaction.support.TransactionTemplate(
                    context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            outer.setReadOnly(true);
            outer.executeWithoutResult(status -> {
                assertThat(approvalResultService.startCall(initial.requestId(),initial.approvalRequestId()).callRequired()).isTrue();
                status.setRollbackOnly();
            });
            assertThat(requestRow(initial).get("approval_creation_status")).isEqualTo("IN_PROGRESS");
            assertThat(requestRow(initial).get("approval_attempt_count")).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("drop trigger t2_isolation on leave_request");
        }
    }

    @Test
    void differentEmployeesRacingForSameDocumentAreProtectedByRealUniqueConstraint() throws Exception {
        LeaveSubmissionResult first = pending();
        currentEmployeeId.set(2L);
        LeaveSubmissionResult second = submissionService.submit("key",input(1,null,date(day,LeaveRequestPortion.FULL)));
        currentEmployeeId.remove();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> firstCall = executor.submit(() -> outcome(start,first,"SUCCESS"));
            Future<Object> secondCall = executor.submit(() -> {
                currentEmployeeId.set(2L);
                try { return outcome(start,second,"SUCCESS"); }
                finally { currentEmployeeId.remove(); }
            });
            start.countDown();
            List<Object> outcomes = List.of(firstCall.get(15,TimeUnit.SECONDS),secondCall.get(15,TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(value->value instanceof RuntimeException).count()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from leave_request where approval_doc_id=901",Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from leave_request where status='SUBMITTING'",Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from leave_ledger where event_type='RESERVE'",Integer.class)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"SUCCESS","FAILURE"})
    void unknownRacingWithConfirmedOutcomeCannotOverwriteIt(String confirmed) throws Exception {
        LeaveSubmissionResult initial = pending();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> confirmedCall = executor.submit(()->outcome(start,initial,confirmed));
            Future<Object> unknownCall = executor.submit(()->outcome(start,initial,"UNKNOWN"));
            start.countDown();
            assertThat(confirmedCall.get(15,TimeUnit.SECONDS)).isInstanceOf(LeaveSubmissionResult.class);
            Object unknownResult = unknownCall.get(15,TimeUnit.SECONDS);
            assertThat(unknownResult instanceof LeaveSubmissionResult || unknownResult instanceof BusinessException).isTrue();
            assertThat(requestRow(initial).get("status")).isEqualTo(confirmed.equals("SUCCESS") ? "SUBMITTED" : "CANCELLED");
            assertThat(requestRow(initial).get("approval_creation_status")).isEqualTo(confirmed.equals("SUCCESS") ? "LINKED" : "FAILED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void twoRequestsSharingGrantAreCompensatedWithoutOverRelease() throws Exception {
        LeaveSubmissionResult first = pending();
        LeaveSubmissionResult second = submissionService.submit("second",input(1,null,date(day.plusDays(1),LeaveRequestPortion.FULL)));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> firstCall = executor.submit(()->outcome(start,first,"FAILURE"));
            Future<Object> secondCall = executor.submit(()->outcome(start,second,"FAILURE"));
            start.countDown();
            assertThat(firstCall.get(15,TimeUnit.SECONDS)).isInstanceOf(LeaveSubmissionResult.class);
            assertThat(secondCall.get(15,TimeUnit.SECONDS)).isInstanceOf(LeaveSubmissionResult.class);
            assertThat(balanceService.getMyBalances().getFirst().reservedMinutes()).isZero();
            assertThat(balanceService.getMyBalances().getFirst().quantityMinutes()).isEqualTo(1440);
            assertThat(jdbcTemplate.queryForObject("select count(*) from leave_ledger where event_type='RELEASE'",Integer.class)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void t1AndT2InSameOpenPersistenceContextUseStoredDateAllocations() {
        jakarta.persistence.EntityManagerFactory factory = context.getBean(jakarta.persistence.EntityManagerFactory.class);
        jakarta.persistence.EntityManager manager = factory.createEntityManager();
        TransactionSynchronizationManager.bindResource(factory,new org.springframework.orm.jpa.EntityManagerHolder(manager));
        try {
            LeaveSubmissionResult initial = pending();
            approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED");
            assertThat(requestRow(initial).get("status")).isEqualTo("CANCELLED");
            assertThat(jdbcTemplate.queryForObject("select sum(reserved_delta) from leave_ledger where account_id=1",Long.class)).isZero();
        } finally {
            TransactionSynchronizationManager.unbindResource(factory);
            manager.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"GLOBAL_RESERVATION_SHORTFALL","FUTURE_RESERVE","MISSING_ALLOCATION","DATE_TOTAL_MISMATCH","FAILED_REPLAY_MISSING_RELEASE"})
    void aggregateAndDateCorruptionAlsoRejectEntireCompensation(String corruption) {
        LeaveSubmissionResult initial = pending();
        switch (corruption) {
            case "GLOBAL_RESERVATION_SHORTFALL" -> jdbcTemplate.update("""
                    insert into leave_ledger(account_id,grant_id,event_type,reserved_delta,idempotency_key)
                    values(1,100,'ADJUST',-240,'shortfall')
                    """);
            case "FUTURE_RESERVE" -> jdbcTemplate.update("update leave_ledger set created_at=clock_timestamp()+interval '1 day' where event_type='RESERVE'");
            case "MISSING_ALLOCATION" -> {
                jdbcTemplate.update("delete from leave_ledger where event_type='RESERVE'");
                jdbcTemplate.update("delete from leave_request_allocation");
            }
            case "DATE_TOTAL_MISMATCH" -> jdbcTemplate.update("update leave_request_date set deduct_minutes=240");
            default -> {
                approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED");
                jdbcTemplate.update("delete from leave_ledger where event_type='RELEASE'");
            }
        }
        Map<String,List<Map<String,Object>>> before = rows();
        assertBusiness(()->approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED"),ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void grantLockWaitUsesLatestCommittedLedgerBeforeCompensation() throws Exception {
        LeaveSubmissionResult initial = pending();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = writer()) {
            writer.createStatement().execute("select grant_id from leave_grant where grant_id=100 for update");
            Future<?> waiting = executor.submit(()->approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED"));
            awaitWait(null);
            writer.createStatement().executeUpdate("update leave_ledger set quantity_delta=200 where entry_id=100");
            writer.commit();
            assertThatThrownBy(()->waiting.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class);
            assertThat(requestRow(initial).get("status")).isEqualTo("SUBMITTING");
            assertThat(jdbcTemplate.queryForObject("select count(*) from leave_ledger where event_type='RELEASE'",Integer.class)).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oneEmployeesAdvisoryWaitDoesNotBlockAnotherEmployee() throws Exception {
        LeaveSubmissionResult first = pending();
        currentEmployeeId.set(2L);
        LeaveSubmissionResult second = submissionService.submit("key",input(1,null,date(day,LeaveRequestPortion.FULL)));
        currentEmployeeId.remove();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection writer = writer()) {
            writer.createStatement().execute("select pg_advisory_xact_lock(-1::bigint)");
            Future<?> waiting = executor.submit(()->approvalResultService.recordUnknown(first.requestId(),first.approvalRequestId(),"TIMEOUT"));
            awaitWait("advisory");
            currentEmployeeId.set(2L);
            assertThat(approvalResultService.linkCreatedDocument(second.requestId(),second.approvalRequestId(),902L).status())
                    .isEqualTo(LeaveRequestStatus.SUBMITTED);
            assertThat(waiting.isDone()).isFalse();
            writer.commit();
            waiting.get(15,TimeUnit.SECONDS);
        } finally {
            currentEmployeeId.remove();
            executor.shutdownNow();
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void finalDecisionSettlesEachAllocationOnce(String status) {
        splitGrants();
        LeaveSubmissionResult initial = submissionService.submit(
                "key", input(1, null, date(day, LeaveRequestPortion.FULL), date(day.plusDays(1), LeaveRequestPortion.FULL))
        );
        LeaveApprovalDecision decision = decision(initial, status, 1, 501L, initial.approvalRequestId());

        assertThat(settlementService.settle(decision))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.AWAITING_DOCUMENT_LINK);
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);

        assertThat(settlementService.settle(decision)).isEqualTo(LeaveApprovalSettlementService.Outcome.APPLIED);
        assertThat(requestRow(initial)).containsEntry("status", status);
        boolean approved = status.equals("APPROVED");
        List<Map<String, Object>> entries = jdbcTemplate.queryForList("""
                select l.event_type, l.quantity_delta, l.reserved_delta, l.actor_emp_id, l.idempotency_key,
                       a.allocated_minutes, l.grant_id, a.grant_id as original_grant_id
                from leave_ledger l join leave_request_allocation a on a.allocation_id = l.allocation_id
                where l.leave_req_id = ? and l.event_type <> 'RESERVE'
                """, initial.requestId());
        assertThat(entries).hasSize(3).allSatisfy(entry -> {
            int minutes = (Integer) entry.get("allocated_minutes");
            assertThat(entry).containsEntry("event_type", approved ? "CONSUME" : "RELEASE")
                    .containsEntry("quantity_delta", approved ? -minutes : 0)
                    .containsEntry("reserved_delta", -minutes)
                    .containsEntry("actor_emp_id", null)
                    .containsEntry("grant_id", entry.get("original_grant_id"));
        });

        Map<String, List<Map<String, Object>>> after = rows();
        assertThat(settlementService.settle(decision))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.ALREADY_APPLIED);
        assertThat(settlementService.settle(decision(
                initial, approved ? "REJECTED" : "APPROVED", 1, 501L, initial.approvalRequestId()
        ))).isEqualTo(LeaveApprovalSettlementService.Outcome.CONFLICT);
        assertThat(rows()).isEqualTo(after);
    }

    @Test
    void finalDecisionRejectsWrongVersionDocumentAndUuidWithoutWriting() {
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);
        Map<String, List<Map<String, Object>>> before = rows();

        assertBusiness(() -> settlementService.settle(decision(
                initial, "APPROVED", 2, 501L, initial.approvalRequestId()
        )), ErrorCode.INVALID_INPUT);
        assertThat(settlementService.settle(decision(initial, "APPROVED", 1, 502L, initial.approvalRequestId())))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.CONFLICT);
        assertThat(settlementService.settle(decision(initial, "APPROVED", 1, 501L, UUID.randomUUID())))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.CONFLICT);
        assertThat(rows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void finalDecisionSettlesExpiredAllocation(String status) {
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);
        jdbcTemplate.update("update leave_grant set expires_on=clock_timestamp()-interval '1 second' where grant_id=100");

        assertThat(settlementService.settle(decision(initial, status, 1, 501L, initial.approvalRequestId())))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.APPLIED);
        assertThat(jdbcTemplate.queryForObject(
                "select sum(reserved_delta) from leave_ledger where grant_id=100", Long.class
        )).isZero();
    }

    @ParameterizedTest
    @CsvSource({"true, APPROVED", "false, APPROVED", "true, CANCEL", "false, CANCEL"})
    void finalDecisionRollsBackWhenLedgerInsertOrRequestUpdateFails(boolean failRequestUpdate, String status) {
        splitGrants();
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);
        String table = failRequestUpdate ? "leave_request" : "leave_ledger";
        String operation = failRequestUpdate ? "update" : "insert";
        boolean withdrawal = status.equals("CANCEL");
        String finalStatus = withdrawal ? "WITHDRAWN" : "APPROVED";
        String ledgerType = withdrawal ? "RELEASE" : "CONSUME";
        String condition = failRequestUpdate ? "new.status='" + finalStatus + "'"
                : "new.event_type='" + ledgerType + "' and new.grant_id=101";
        jdbcTemplate.execute("create or replace function settlement_test_fail() returns trigger language plpgsql "
                + "as $$ begin if " + condition + " then raise exception 'settlement rollback'; end if; return new; end $$");
        jdbcTemplate.execute("create trigger settlement_fail before " + operation + " on " + table
                + " for each row execute function settlement_test_fail()");
        Map<String, List<Map<String, Object>>> before = rows();
        try {
            LeaveApprovalDecision decision = decision(initial, status, 1, 501L, initial.approvalRequestId());
            assertThatThrownBy(() -> settlementService.settle(decision)).isInstanceOf(RuntimeException.class);
            assertThat(rows()).isEqualTo(before);
        } finally {
            jdbcTemplate.execute("drop trigger settlement_fail on " + table);
        }
    }

    @Test
    void finalDecisionRejectsMissingReserve() {
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);
        jdbcTemplate.update("delete from leave_ledger where event_type='RESERVE'");
        Map<String, List<Map<String, Object>>> before = rows();

        LeaveApprovalDecision decision = decision(initial, "APPROVED", 1, 501L, initial.approvalRequestId());
        assertBusiness(() -> settlementService.settle(decision), ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(rows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void finalDecisionOnNonDeductingRequestWritesNoLedger(String status) {
        LeaveSubmissionResult initial = submissionService.submit("key", input(2, null, date(day, LeaveRequestPortion.FULL)));
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);
        int originalLedgerCount = count("leave_ledger");

        assertThat(settlementService.settle(decision(initial, status, 1, 501L, initial.approvalRequestId())))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.APPLIED);
        assertThat(requestRow(initial)).containsEntry("status", status);
        assertThat(count("leave_ledger")).isEqualTo(originalLedgerCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void concurrentFinalDecisionsOnlySettleOnce(String otherStatus) throws Exception {
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<LeaveApprovalSettlementService.Outcome> first = workers.submit(() -> {
                start.await();
                return settlementService.settle(decision(initial, "APPROVED", 1, 501L, initial.approvalRequestId()));
            });
            Future<LeaveApprovalSettlementService.Outcome> second = workers.submit(() -> {
                start.await();
                return settlementService.settle(decision(initial, otherStatus, 1, 501L, initial.approvalRequestId()));
            });
            start.countDown();

            LeaveApprovalSettlementService.Outcome expectedOther = otherStatus.equals("APPROVED")
                    ? LeaveApprovalSettlementService.Outcome.ALREADY_APPLIED : LeaveApprovalSettlementService.Outcome.CONFLICT;
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(LeaveApprovalSettlementService.Outcome.APPLIED, expectedOther);
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from leave_ledger where leave_req_id=? and event_type in ('CONSUME','RELEASE')",
                Integer.class, initial.requestId()
        )).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void pendingAdminCancellationDoesNotSuppressDecisionAndLateCompletionCannotReleaseAgain(String status) {
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);
        when(employeeAccess.isAdmin(1L)).thenReturn(true);
        AdminCancellationAttempt attempt = cancellationTransactions.start(initial.requestId(), "관리자 취소");
        cancellationTransactions.recordUnknown(attempt.command());

        assertThat(settlementService.settle(decision(initial, status, 1, 501L, initial.approvalRequestId())))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.APPLIED);
        Map<String, List<Map<String, Object>>> after = rows();

        assertBusiness(() -> cancellationTransactions.complete(attempt.command()), ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED);
        assertThat(rows()).isEqualTo(after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void adjustmentAndFinalDecisionPreserveSharedGrantTotals(String status) throws Exception {
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);
        when(employeeAccess.isAdmin(1L)).thenReturn(true);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> adjusted = workers.submit(() -> {
                start.await();
                return adjustments.adjust(1L, "decision-race", new LeaveAdjustmentRequest(1L, 100L, -240, "정정"));
            });
            Future<LeaveApprovalSettlementService.Outcome> settled = workers.submit(() -> {
                start.await();
                return settlementService.settle(decision(initial, status, 1, 501L, initial.approvalRequestId()));
            });
            start.countDown();
            adjusted.get(10, TimeUnit.SECONDS);
            assertThat(settled.get(10, TimeUnit.SECONDS)).isEqualTo(LeaveApprovalSettlementService.Outcome.APPLIED);
        }

        assertThat(balanceService.getMyBalances().getFirst().reservedMinutes()).isZero();
        assertThat(balanceService.getMyBalances().getFirst().quantityMinutes())
                .isEqualTo(status.equals("APPROVED") ? 720 : 1200);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2})
    void drafterWithdrawalIsAuditedAndIdempotent(long leaveTypeId) {
        splitGrants();
        LeaveSubmissionResult initial = submissionService.submit(
                "key", input(leaveTypeId, null, date(day, LeaveRequestPortion.FULL), date(day.plusDays(1), LeaveRequestPortion.FULL))
        );
        LeaveApprovalDecision cancel = decision(initial, "CANCEL", 1, 501L, initial.approvalRequestId());
        assertThat(settlementService.settle(cancel))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.AWAITING_DOCUMENT_LINK);
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);

        assertThat(settlementService.settle(cancel)).isEqualTo(LeaveApprovalSettlementService.Outcome.APPLIED);
        assertThat(requestRow(initial)).containsEntry("status", "WITHDRAWN")
                .containsEntry("cancelled_by", 1L)
                .containsEntry("cancel_reason", "기안자 결재 문서 회수")
                .containsEntry("admin_cancel_status", null);
        assertThat(requestRow(initial).get("cancelled_at")).isNotNull();
        List<Map<String, Object>> releases = jdbcTemplate.queryForList("""
                select quantity_delta, reserved_delta, actor_emp_id, idempotency_key
                from leave_ledger where leave_req_id=? and event_type='RELEASE'
                """, initial.requestId());
        assertThat(releases).hasSize(leaveTypeId == 1 ? 3 : 0).allSatisfy(release -> {
            assertThat(release).containsEntry("quantity_delta", 0).containsEntry("actor_emp_id", null);
            assertThat((Integer) release.get("reserved_delta")).isNegative();
            assertThat((String) release.get("idempotency_key")).startsWith("leave:approval:withdraw:request:");
        });
        Map<String, List<Map<String, Object>>> after = rows();
        assertThat(settlementService.settle(cancel)).isEqualTo(LeaveApprovalSettlementService.Outcome.ALREADY_APPLIED);
        assertThat(settlementService.settle(decision(initial, "APPROVED", 1, 501L, initial.approvalRequestId())))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.CONFLICT);
        assertThat(rows()).isEqualTo(after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED", "IN_PROGRESS", "UNKNOWN", "CANCELLED"})
    void withdrawalDoesNotOverwriteTerminalDecisionOrAmbiguousAdminCancellation(String priorState) {
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);
        if (priorState.equals("APPROVED") || priorState.equals("REJECTED")) {
            settlementService.settle(decision(initial, priorState, 1, 501L, initial.approvalRequestId()));
        } else {
            when(employeeAccess.isAdmin(1L)).thenReturn(true);
            AdminCancellationAttempt attempt = cancellationTransactions.start(initial.requestId(), "관리자 취소");
            if (priorState.equals("UNKNOWN")) {
                cancellationTransactions.recordUnknown(attempt.command());
            }
            if (priorState.equals("CANCELLED")) {
                cancellationTransactions.complete(attempt.command());
            }
        }

        Map<String, List<Map<String, Object>>> before = rows();
        assertThat(settlementService.settle(decision(initial, "CANCEL", 1, 501L, initial.approvalRequestId())))
                .isEqualTo(LeaveApprovalSettlementService.Outcome.CONFLICT);
        assertThat(rows()).isEqualTo(before);
    }

    private LeaveApprovalDecision decision(
            LeaveSubmissionResult initial, String status, int version, Long documentId, UUID requestKey
    ) {
        return new LeaveApprovalDecision(
                version, "DOCUMENT_COMPLETED", "LEAVE", "LEAVE", initial.requestId(), documentId, requestKey, status
        );
    }

    @Test
    void lateHttpFailurePreservesRecoveredDocumentAndReservation() {
        LeaveSubmissionResult initial = pending();
        approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
        approvalResultService.recordUnknownAfterHttp(initial.requestId(), initial.approvalRequestId(), "TIMEOUT");
        LeaveSubmissionResult linked = approvalResultService.linkCreatedDocument(
                initial.requestId(), initial.approvalRequestId(), 501L
        );
        Map<String, List<Map<String, Object>>> after = rows();

        assertThat(approvalResultService.recordUnknownAfterHttp(
                initial.requestId(), initial.approvalRequestId(), "APPROVAL_HTTP_404"
        )).isEqualTo(linked);
        assertThat(rows()).isEqualTo(after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMITTING", "SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN", "CANCELLED"})
    void internalDatesUseStoredRowsRegardlessOfRequestState(String state) throws Exception {
        LeaveSubmissionResult initial = submissionService.submit("dates", input(1, null,
                date(day.plusDays(2), LeaveRequestPortion.PM_HALF),
                date(day, LeaveRequestPortion.FULL),
                date(day.plusDays(1), LeaveRequestPortion.AM_HALF)));
        submissionService.submit("other", input(1, null, date(day.plusDays(3), LeaveRequestPortion.FULL)));
        if (!state.equals("SUBMITTING")) {
            approvalResultService.startCall(initial.requestId(), initial.approvalRequestId());
            approvalResultService.linkCreatedDocument(initial.requestId(), initial.approvalRequestId(), 501L);
            jdbcTemplate.update("update leave_request set status=? where leave_req_id=?", state, initial.requestId());
        }
        Map<String, List<Map<String, Object>>> before = rows();

        // 실제 보안 필터를 거친 익명 GET에서도 저장된 날짜를 조회한다.
        MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build().perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/leaves/internal/requests/{requestId}/dates", initial.requestId()
                )
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.requestId").value(initial.requestId()))
                .andExpect(jsonPath("$.data.employeeId").value(initial.employeeId()))
                .andExpect(jsonPath("$.data.leaveTypeName").value("연차"))
                .andExpect(jsonPath("$.data.isPaid").value(true))
                .andExpect(jsonPath("$.data.dates.length()").value(3))
                .andExpect(jsonPath("$.data.dates[0].leaveDate").value(day.toString()))
                .andExpect(jsonPath("$.data.dates[0].portion").value("FULL"))
                .andExpect(jsonPath("$.data.dates[1].portion").value("AM_HALF"))
                .andExpect(jsonPath("$.data.dates[2].portion").value("PM_HALF"));
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void internalDatesReturnCurrentNameAndActualPayStatus() throws Exception {
        jdbcTemplate.update("update leave_type set is_paid=false where leave_type_id=2");
        LeaveSubmissionResult unpaid = submissionService.submit(
                "unpaid", input(2, null, date(day, LeaveRequestPortion.FULL))
        );
        submissionService.submit("paid", input(1, null, date(day.plusDays(1), LeaveRequestPortion.FULL)));
        jdbcTemplate.update("update leave_type set name='무급 특별휴가' where leave_type_id=2");

        MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build().perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/leaves/internal/requests/{requestId}/dates", unpaid.requestId()
                )
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.leaveTypeName").value("무급 특별휴가"))
                .andExpect(jsonPath("$.data.isPaid").value(false))
                .andExpect(jsonPath("$.data.dates.length()").value(1));
    }

    @Test
    void internalDatesDistinguishMissingRequestAndMissingStoredDates() throws Exception {
        LeaveSubmissionResult initial = pending();
        LeaveRequestDatesService datesService = context.getBean(LeaveRequestDatesService.class);
        assertBusiness(() -> datesService.getDates(Long.MAX_VALUE), ErrorCode.NOT_FOUND);
        assertBusiness(() -> datesService.getDates(0L), ErrorCode.INVALID_INPUT);
        org.springframework.test.web.servlet.MockMvc internalMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        String path = "/api/v1/leaves/internal/requests/{requestId}/dates";
        internalMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path, Long.MAX_VALUE))
                .andExpect(status().isNotFound());
        internalMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path, 0))
                .andExpect(status().isBadRequest());
        jdbcTemplate.update("delete from leave_ledger where leave_req_id=?", initial.requestId());
        jdbcTemplate.update("delete from leave_request_allocation where leave_req_id=?", initial.requestId());
        jdbcTemplate.update("delete from leave_request_date where leave_req_id=?", initial.requestId());
        assertBusiness(() -> datesService.getDates(initial.requestId()), ErrorCode.DATA_INTEGRITY_VIOLATION);
        internalMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path, initial.requestId()))
                .andExpect(status().isConflict());
    }

    private LeaveSubmissionResult pending() {
        return submissionService.submit("key",input(1,null,date(day,LeaveRequestPortion.FULL)));
    }

    private Map<String,Object> requestRow(LeaveSubmissionResult result) {
        return jdbcTemplate.queryForMap("select *,xmin::text as xmin from leave_request where leave_req_id=?",result.requestId());
    }

    private void splitGrants() {
        jdbcTemplate.update("update leave_ledger set quantity_delta=240 where entry_id=100");
        jdbcTemplate.update("insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from) values(101,1,'MANUAL',960,'2000-01-01Z')");
        jdbcTemplate.update("insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at) values(1,101,'GRANT',960,'second-grant','2000-01-01Z')");
    }

    private Object outcome(CountDownLatch start, LeaveSubmissionResult initial, String kind) throws Exception {
        start.await();
        try {
            return switch (kind) {
                case "SUCCESS" -> approvalResultService.linkCreatedDocument(initial.requestId(),initial.approvalRequestId(),901L);
                case "START" -> approvalResultService.startCall(initial.requestId(),initial.approvalRequestId());
                case "UNKNOWN" -> approvalResultService.recordUnknown(initial.requestId(),initial.approvalRequestId(),"TIMEOUT");
                default -> approvalResultService.confirmNotCreated(initial.requestId(),initial.approvalRequestId(),"NOT_CREATED");
            };
        } catch (RuntimeException exception) {
            return exception;
        }
    }
    private Connection writer() throws Exception {
        Connection connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        connection.setAutoCommit(false);
        return connection;
    }

    private void awaitWait(String event) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            jdbcTemplate.execute("select pg_stat_clear_snapshot()");
            Integer count=jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity where wait_event_type='Lock' and pid<>pg_backend_pid()
                        and (cast(? as text) is null or wait_event=cast(? as text))
                    """, Integer.class, event, event);
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("직원/정책 잠금 대기가 실제 DB에 나타나야 한다.");
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private Map<String,List<Map<String,Object>>> rows() {
        Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
        for (String table : TABLES) {
            result.put(table, jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
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
