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
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "leave.test.database-owner=LeaveSubmissionPostgresIntegrationTest",
        "spring.cloud.config.enabled=false","eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/","spring.jpa.open-in-view=false"})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeaveSubmissionPostgresIntegrationTest {
    @Autowired
    private PostgreSQLContainer postgres;
    @Autowired JdbcTemplate jdbc;
    @Autowired LeaveSubmissionService service;
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
        employee.remove();
        when(access.currentEmployeeId()).thenAnswer(invocation->employee.get());
        when(holidays.findHolidays(any(),any())).thenAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new HolidayResult(true,List.of());
        });
        jdbc.update("update leave_policy_group set active_policy_version_id=null");
        for(String table:TABLES) { jdbc.update("delete from "+table); }
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

    @ParameterizedTest
    @CsvSource({"FULL,480","AM_HALF,240","PM_HALF,240"})
    void savesAllRowsAndBalancesReserveCorrectly(LeaveRequestPortion portion,int minutes) {
        LeaveSubmissionResult result=service.submit("key",input(1,null,date(day,portion)));
        assertThat(result.status()).isEqualTo(LeaveRequestStatus.SUBMITTING);
        assertThat(result.approvalCreationStatus()).isEqualTo(ApprovalCreationStatus.NOT_SENT);
        assertThat(result.approvalDocId()).isNull();
        assertThat(result.approvalRequestId()).isNotNull();
        Map<String,Object> request=jdbc.queryForMap("select * from leave_request where leave_req_id=?",result.requestId());
        assertThat(request.get("total_minutes")).isEqualTo(minutes);
        assertThat(request.get("total_deduct_minutes")).isEqualTo(minutes);
        assertThat(request.get("policy_version_id")).isEqualTo(11L);
        assertThat(request.get("emp_id")).isEqualTo(1L);
        assertThat(request.get("approval_attempt_count")).isEqualTo(0);
        assertThat(request.get("created_at")).isEqualTo(request.get("updated_at"));
        assertThat(request.get("approval_status_changed_at")).isEqualTo(request.get("created_at"));
        Map<String,Object> storedDate=jdbc.queryForMap("select * from leave_request_date where leave_req_id=?",result.requestId());
        assertThat(storedDate.get("scheduled_work_minutes")).isEqualTo(480);
        assertThat(storedDate.get("requested_minutes")).isEqualTo(minutes);
        assertThat(storedDate.get("deduct_minutes")).isEqualTo(minutes);
        assertThat(storedDate.get("start_at")).isNull();
        assertThat(storedDate.get("end_at")).isNull();
        OffsetDateTime stored=jdbc.queryForObject("select leave_date from leave_request_date where leave_req_id=?",
                (row,index)->row.getObject(1,OffsetDateTime.class),result.requestId());
        assertThat(stored.toInstant()).isEqualTo(day.atStartOfDay(PreviewGrantAllocator.SEOUL).toInstant());
        assertThat(count("leave_request_allocation")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type='RESERVE' and quantity_delta=0 and reserved_delta=? and allocation_id is not null",Integer.class,minutes)).isEqualTo(1);
        assertThat(balances.getMyBalances().getFirst().reservedMinutes()).isEqualTo(minutes);
        assertThat(balances.getMyBalances().getFirst().remainingMinutes()).isEqualTo(1440-minutes);
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type in ('CONSUME','RELEASE')",Integer.class)).isZero();
        verify(access,never()).isAdmin(any());
    }

    @Test
    void splitFefoAcrossDatesHasExactDateAndRequestSums() {
        jdbc.update("update leave_grant set expires_on=? where grant_id=100",day.plusDays(10).atStartOfDay(PreviewGrantAllocator.SEOUL).toOffsetDateTime());
        jdbc.update("update leave_ledger set quantity_delta=240 where entry_id=100");
        jdbc.update("""
                insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from)
                values(101,1,'MANUAL',99999,'2000-01-01Z')
                """);
        jdbc.update("""
                insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                values(1,101,'GRANT',960,'second','2000-01-01Z')
                """);
        LeaveSubmissionResult result=service.submit("split",input(1,"사유",date(day.plusDays(3),LeaveRequestPortion.AM_HALF),date(day,LeaveRequestPortion.FULL)));
        List<Map<String,Object>> rows=jdbc.queryForList("""
                select a.grant_id,a.allocated_minutes from leave_request_allocation a join leave_request_date d
                    on d.request_date_id=a.request_date_id order by d.leave_date,a.allocation_id
                """);
        assertThat(rows).containsExactly(Map.of("grant_id",100L,"allocated_minutes",240),
                Map.of("grant_id",101L,"allocated_minutes",240),Map.of("grant_id",101L,"allocated_minutes",240));
        assertThat(jdbc.queryForObject("select sum(allocated_minutes) from leave_request_allocation",Long.class)).isEqualTo(720);
        assertThat(jdbc.queryForObject("select total_deduct_minutes from leave_request where leave_req_id=?",Integer.class,result.requestId())).isEqualTo(720);
        assertThat(jdbc.queryForObject("""
                select count(*) from leave_request_date d where d.deduct_minutes <>
                    (select sum(a.allocated_minutes) from leave_request_allocation a where a.request_date_id=d.request_date_id)
                """,Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(distinct idempotency_key) from leave_ledger where event_type='RESERVE'",Integer.class)).isEqualTo(3);
    }

    @Test
    void noneStillStoresDatesAndBlocksDuplicateWithoutAllocations() {
        service.submit("none",input(2,"선택",date(day,LeaveRequestPortion.FULL)));
        assertThat(count("leave_request")).isEqualTo(1);
        assertThat(count("leave_request_allocation")).isZero();
        assertThat(jdbc.queryForObject("select total_deduct_minutes from leave_request",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select deduct_minutes from leave_request_date",Integer.class)).isZero();
        assertThat(count("leave_ledger")).isEqualTo(2);
        assertBusiness(()->service.submit("another",input(2,null,date(day,LeaveRequestPortion.PM_HALF))),ErrorCode.LEAVE_REQUEST_NOT_ALLOWED);
    }

    @Test
    void retryReorderedDatesAndNewServiceInstanceReturnSameRowsEvenHolidayUnavailable() {
        LeaveRequestPreviewRequest first=input(1,"  보존  ",date(day,LeaveRequestPortion.FULL),date(day.plusDays(1),LeaveRequestPortion.AM_HALF));
        LeaveSubmissionResult original=service.submit("retry",first);
        Map<String,List<Map<String,Object>>> before=rows();
        when(holidays.findHolidays(any(),any())).thenReturn(HolidayResult.unavailable());
        LeaveRequestPreviewRequest reordered=input(1,"  보존  ",date(day.plusDays(1),LeaveRequestPortion.AM_HALF),date(day,LeaveRequestPortion.FULL));
        assertThat(service.submit("retry",reordered)).isEqualTo(original);
        LeaveSubmissionService restarted = new LeaveSubmissionService(access, validator, hash, holidays, transactionService);
        assertThat(restarted.submit("retry", reordered)).isEqualTo(original);
        assertThat(transactionService.findExisting("retry",hash.calculate(first))).contains(original);
        assertThat(rows()).isEqualTo(before);
        verify(holidays,times(1)).findHolidays(any(),any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings={""," ","다른 사유"})
    void sameKeyDifferentReasonConflicts(String reason) {
        service.submit("key",input(1,"original",date(day,LeaveRequestPortion.FULL)));
        Map<String,List<Map<String,Object>>> before=rows();
        assertBusiness(()->service.submit("key",input(1,reason,date(day,LeaveRequestPortion.FULL))),ErrorCode.LEAVE_SUBMISSION_KEY_CONFLICT);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void differentEmployeesCanUseSameKeyAndDate() {
        LeaveSubmissionResult first=service.submit("same",input(1,null,date(day,LeaveRequestPortion.FULL)));
        employee.set(2L);
        LeaveSubmissionResult second=service.submit("same",input(1,null,date(day,LeaveRequestPortion.FULL)));
        assertThat(first.requestId()).isNotEqualTo(second.requestId());
        assertThat(second.employeeId()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings={""," ","a b","\t","한글"})
    void invalidKeysAreRejectedBeforeHolidayOrWrites(String key) {
        assertBusiness(()->service.submit(key,input(1,null,date(day,LeaveRequestPortion.FULL))),ErrorCode.INVALID_INPUT);
        verifyNoInteractions(holidays);
        assertThat(count("leave_request")).isZero();
    }

    @Test
    void holidayFailureAndHolidayOrWeekendCannotPersistAnything() {
        when(holidays.findHolidays(any(),any())).thenReturn(HolidayResult.unavailable());
        assertBusiness(()->service.submit("fail",input(1,null,date(day,LeaveRequestPortion.FULL))),ErrorCode.HOLIDAY_VERIFICATION_UNAVAILABLE);
        when(holidays.findHolidays(any(),any())).thenReturn(new HolidayResult(true,List.of(new Holiday(day,"회사",HolidayType.COMPANY))));
        assertBusiness(()->service.submit("holiday",input(1,null,date(day,LeaveRequestPortion.FULL))),ErrorCode.LEAVE_REQUEST_NOT_ALLOWED);
        when(holidays.findHolidays(any(),any())).thenReturn(new HolidayResult(true,List.of()));
        assertBusiness(()->service.submit("weekend",input(1,null,date(day.plusDays(5),LeaveRequestPortion.FULL))),ErrorCode.LEAVE_REQUEST_NOT_ALLOWED);
        assertThat(count("leave_request")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={
        "update leave_type set is_active=false where leave_type_id=1",
        "update leave_ledger set quantity_delta=200 where entry_id=100",
        "delete from leave_ledger where account_id=1",
        "update leave_grant set valid_from=clock_timestamp()+interval '100 years' where grant_id=100",
        "update leave_policy_version set unit_minutes=17 where policy_version_id=11"
    })
    void businessValidationFailureDoesNotSave(String sql) {
        jdbc.update(sql);
        assertBusiness(()->service.submit("invalid",input(1,null,date(day,LeaveRequestPortion.FULL))),ErrorCode.LEAVE_REQUEST_NOT_ALLOWED);
        assertThat(count("leave_request")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"leave_request","leave_request_date","leave_request_allocation","leave_ledger"})
    void exceptionAfterAnyInsertRollsBackEveryRow(String table) {
        jdbc.execute("""
                create or replace function t1_test_reject() returns trigger language plpgsql as $$
                begin raise exception 'isolated T1 insert failure' using errcode='23514'; end $$;
                """);
        jdbc.execute("create trigger t1_fail after insert on "+table+" for each row execute function t1_test_reject()");
        Map<String,List<Map<String,Object>>> before=rows();
        try {
            assertThatThrownBy(()->service.submit("rollback",input(1,null,date(day,LeaveRequestPortion.FULL))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(rows()).isEqualTo(before);
        } finally {
            jdbc.execute("drop trigger t1_fail on "+table);
        }
    }

    @Test
    void publicHttpSubmissionEndpointIsPreparedButDisabledBeforeT1() throws Exception {
        Map<String,List<Map<String,Object>>> before = rows();
        MockMvcBuilders.webAppContextSetup(context).build().perform(post("/api/v1/leaves/me/requests")
                        .header("Idempotency-Key", "disabled")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"leaveTypeId\":1,\"dates\":[{\"leaveDate\":\"" + day + "\",\"portion\":\"FULL\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("LEAVE_016"));
        assertThat(rows()).isEqualTo(before);
        verifyNoInteractions(holidays);
    }

    @ParameterizedTest
    @ValueSource(strings={"SAME_DATE","BALANCE","SAME_KEY"})
    void concurrentSubmissionsSerializeAndDoNotDoubleReserve(String mode) throws Exception {
        jdbc.update("update leave_ledger set quantity_delta=480 where entry_id=100");
        CountDownLatch start=new CountDownLatch(1);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<Object> first=executor.submit(()->concurrentSubmit(start,"same",day));
            Future<Object> second=executor.submit(()->concurrentSubmit(start,mode.equals("SAME_KEY")?"same":"other",
                    mode.equals("BALANCE")?day.plusDays(1):day));
            start.countDown();
            List<Object> results=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            assertThat(count("leave_request")).isEqualTo(1);
            assertThat(count("leave_request_allocation")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select sum(reserved_delta) from leave_ledger where account_id=1",Long.class)).isEqualTo(480);
            if(mode.equals("SAME_KEY")) {
                assertThat(results.get(0)).isEqualTo(results.get(1));
            } else {
                assertThat(results.stream().filter(LeaveSubmissionResult.class::isInstance).count()).isEqualTo(1);
                assertThat(results.stream().filter(BusinessException.class::isInstance).count()).isEqualTo(1);
            }
        } finally { executor.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings={"RESERVE","ASSIGNMENT","OTHER_EMPLOYEE"})
    void employeeLockWaitReadsFreshStateAndDoesNotSerializeOtherEmployees(String mode) throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(Connection writer=writer()) {
            writer.createStatement().execute("select pg_advisory_xact_lock(-1)");
            Future<LeaveSubmissionResult> waiting=executor.submit(()->service.submit("wait",input(1,null,date(day,LeaveRequestPortion.FULL))));
            awaitWait("advisory");
            if(mode.equals("RESERVE")) {
                writer.createStatement().executeUpdate("""
                        insert into leave_ledger(account_id,grant_id,event_type,reserved_delta,idempotency_key,created_at)
                        values(1,100,'RESERVE',1200,'late',clock_timestamp())
                        """);
            } else if(mode.equals("ASSIGNMENT")) {
                writer.createStatement().executeUpdate("update employee_leave_policy_assignment set policy_id=2 where assignment_id=1");
            } else {
                employee.set(2L);
                service.submit("independent",input(1,null,date(day,LeaveRequestPortion.FULL)));
                employee.remove();
            }
            writer.commit();
            if(mode.equals("RESERVE")) {
                assertThatThrownBy(()->waiting.get(15,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
                assertThat(count("leave_request")).isZero();
            } else {
                LeaveSubmissionResult result=waiting.get(15,TimeUnit.SECONDS);
                if(mode.equals("ASSIGNMENT")) {
                    assertThat(jdbc.queryForObject("select policy_version_id from leave_request where leave_req_id=?",Long.class,result.requestId())).isEqualTo(21);
                } else { assertThat(count("leave_request")).isEqualTo(2); }
            }
        } finally { executor.shutdownNow(); employee.remove(); }
    }

    @Test
    void activationLockWaitSelectsNewVersion() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(Connection writer=writer()) {
            writer.createStatement().execute("select policy_id from leave_policy_group where policy_id=1 for update");
            Future<LeaveSubmissionResult> waiting=executor.submit(()->service.submit("version",input(1,null,date(day,LeaveRequestPortion.FULL))));
            awaitWait(null);
            writer.createStatement().executeUpdate("update leave_policy_group set active_policy_version_id=12 where policy_id=1");
            writer.commit();
            LeaveSubmissionResult result=waiting.get(15,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select policy_version_id from leave_request where leave_req_id=?",Long.class,result.requestId())).isEqualTo(12);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void replayAfterLaterApprovalLinkPreservesExistingIdentityAndReservation() {
        LeaveRequestPreviewRequest input = input(1, null, date(day, LeaveRequestPortion.FULL));
        LeaveSubmissionResult initial = service.submit("linked", input);
        jdbc.update("""
                update leave_request set status='SUBMITTED',approval_creation_status='LINKED',approval_doc_id=123,
                    submitted_at=clock_timestamp() where leave_req_id=?
                """, initial.requestId());
        Map<String,List<Map<String,Object>>> before = rows();
        LeaveSubmissionResult replay = service.submit("linked", input);
        assertThat(replay.requestId()).isEqualTo(initial.requestId());
        assertThat(replay.approvalRequestId()).isEqualTo(initial.approvalRequestId());
        assertThat(replay.status()).isEqualTo(LeaveRequestStatus.SUBMITTED);
        assertThat(replay.approvalDocId()).isEqualTo(123);
        assertThat(rows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings={"FK","CHECK","LEDGER_KEY"})
    void realAllocationAndLedgerConstraintsRollBackParentRows(String failure) {
        String table = failure.equals("LEDGER_KEY") ? "leave_ledger" : "leave_request_allocation";
        String change = switch (failure) {
            case "FK" -> "new.account_id=2;";
            case "CHECK" -> "new.allocated_minutes=0;";
            default -> "new.idempotency_key='own';";
        };
        jdbc.execute("create or replace function t1_test_corrupt() returns trigger language plpgsql as $$ begin "
                + change + " return new; end $$");
        jdbc.execute("create trigger t1_corrupt before insert on " + table + " for each row execute function t1_test_corrupt()");
        Map<String,List<Map<String,Object>>> before = rows();
        try {
            assertThatThrownBy(() -> service.submit("constraints", input(1,null,date(day,LeaveRequestPortion.FULL))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(rows()).isEqualTo(before);
        } finally {
            jdbc.execute("drop trigger t1_corrupt on " + table);
        }
    }

    @Test
    void requestUniqueConstraintFailureDoesNotSavePartialRows() {
        service.submit("seed",input(1,null,date(day,LeaveRequestPortion.FULL)));
        jdbc.execute("""
                create or replace function t1_test_key_conflict() returns trigger language plpgsql as $$
                begin new.submission_key='seed'; return new; end $$
                """);
        jdbc.execute("create trigger t1_key_conflict before insert on leave_request for each row execute function t1_test_key_conflict()");
        Map<String,List<Map<String,Object>>> before = rows();
        try {
            assertThatThrownBy(() -> service.submit("conflict",input(1,null,date(day.plusDays(1),LeaveRequestPortion.FULL))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(rows()).isEqualTo(before);
        } finally {
            jdbc.execute("drop trigger t1_key_conflict on leave_request");
        }
    }

    private Object concurrentSubmit(CountDownLatch start,String key,LocalDate date) throws Exception {
        start.await();
        try { return service.submit(key,input(1,null,date(date,LeaveRequestPortion.FULL))); }
        catch(BusinessException exception) { return exception; }
    }

    private Connection writer() throws Exception {
        Connection connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        connection.setAutoCommit(false);
        return connection;
    }

    private void awaitWait(String event) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            jdbc.execute("select pg_stat_clear_snapshot()");
            Integer count=jdbc.queryForObject("""
                    select count(*) from pg_stat_activity where wait_event_type='Lock' and pid<>pg_backend_pid()
                    """,Integer.class);
            if(count!=null && count>0) { return; }
            Thread.sleep(20);
        }
        fail("직원/정책 잠금 대기가 실제 DB에 나타나야 한다.");
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from "+table,Integer.class); }
    private Map<String,List<Map<String,Object>>> rows() {
        Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
        for(String table:TABLES) { result.put(table,jdbc.queryForList("select *,xmin::text as row_version from "+table+" order by 1")); }
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
