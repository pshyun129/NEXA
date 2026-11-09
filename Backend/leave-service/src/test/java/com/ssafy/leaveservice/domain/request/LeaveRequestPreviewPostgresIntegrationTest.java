package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.client.attendance.HolidayClient;
import com.ssafy.leaveservice.client.attendance.HolidayClient.*;
import com.ssafy.leaveservice.domain.request.service.LeaveRequestPreviewService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.*;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "leave.test.database-owner=LeaveRequestPreviewPostgresIntegrationTest",
        "spring.cloud.config.enabled=false","eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/","spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.request.LeaveRequestPreviewPostgresIntegrationTest$SqlCapture"
})
@ActiveProfiles("dev")
@Import(PostgresTestConfiguration.class)
class LeaveRequestPreviewPostgresIntegrationTest {
    public static class SqlCapture implements StatementInspector {
        static final ThreadLocal<List<String>> sql=ThreadLocal.withInitial(ArrayList::new);
        static final ThreadLocal<Boolean> gated=ThreadLocal.withInitial(()->false);
        static volatile CountDownLatch entered;
        static volatile CountDownLatch release;
        static final ThreadLocal<List<Boolean>> readOnly=ThreadLocal.withInitial(ArrayList::new);
        static final ThreadLocal<List<Integer>> isolation=ThreadLocal.withInitial(ArrayList::new);
        public SqlCapture() {}
        public String inspect(String statement) {
            sql.get().add(statement.toLowerCase());
            readOnly.get().add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            isolation.get().add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            if(gated.get() && sql.get().size()==2) {
                entered.countDown();
                try {
                    if(!release.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("스냅샷 테스트 대기 초과"); }
                } catch(InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return statement;
        }
    }

    private static final String URL="/api/v1/leaves/me/requests/preview";
    private static final OffsetDateTime AS_OF=OffsetDateTime.parse("2030-01-07T00:00:00+09:00");
    private static final List<String> TABLES=List.of("leave_ledger","leave_request_date","leave_request","leave_grant",
            "leave_accrual_rule","leave_policy_type_rule","employee_leave_policy_assignment","leave_account",
            "leave_type","leave_balance_type","leave_policy_version","leave_policy_group");
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean EmployeeAccess access;
    @MockitoBean HolidayClient holidays;
    @MockitoBean Clock leavePreviewClock;
    @Autowired LeaveRequestPreviewService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc=MockMvcBuilders.webAppContextSetup(context).build();
        SqlCapture.sql.get().clear(); SqlCapture.readOnly.get().clear(); SqlCapture.isolation.get().clear();
        SqlCapture.gated.remove();
        when(access.currentEmployeeId()).thenReturn(1L);
        when(access.isAdmin(any())).thenReturn(false);
        when(leavePreviewClock.instant()).thenReturn(AS_OF.toInstant());
        when(leavePreviewClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(holidays.findHolidays(any(),any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new HolidayResult(true,List.of());
        });
        jdbc.update("update leave_policy_group set active_policy_version_id=null");
        for(String table:TABLES) { jdbc.update("delete from "+table); }
        jdbc.update("insert into leave_policy_group(policy_id,name) values(1,'정책'),(2,'타인 정책')");
        jdbc.update("""
                insert into leave_policy_version(policy_version_id,policy_id,version_no,base_type,unit_minutes)
                values(11,1,1,'HIRE_DATE',30),(12,1,2,'HIRE_DATE',17),(21,2,1,'HIRE_DATE',30)
                """);
        jdbc.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbc.update("update leave_policy_group set active_policy_version_id=21 where policy_id=2");
        jdbc.update("""
                insert into employee_leave_policy_assignment(assignment_id,emp_id,policy_id,effective_from)
                values(1,1,1,'2000-01-01Z'),(2,2,2,'2000-01-01Z')
                """);
        jdbc.update("insert into leave_balance_type(balance_type_id,name,is_active) values(1,'연차',false)");
        jdbc.update("""
                insert into leave_type(leave_type_id,balance_type_id,name,balance_mode,request_unit)
                values(1,1,'연차','ACCOUNT','MIXED'),(2,null,'비차감','NONE','MIXED'),(3,1,'다른 유형','ACCOUNT','MIXED')
                """);
        jdbc.update("""
                insert into leave_policy_type_rule(rule_id,policy_version_id,leave_type_id,allowed_units)
                values(1,11,1,'MIXED'),(2,11,2,'MIXED'),(3,11,3,'MIXED')
                """);
        jdbc.update("insert into leave_account(account_id,emp_id,balance_type_id) values(1,1,1),(2,2,1)");
        jdbc.update("""
                insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from,expires_on)
                values(100,1,'MANUAL',99999,'2020-01-01Z','2030-02-01Z'),(200,2,'MANUAL',99999,'2020-01-01Z',null)
                """);
        jdbc.update("""
                insert into leave_ledger(entry_id,account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                values(100,1,100,'GRANT',1440,'own','2029-01-01Z'),(200,2,200,'GRANT',99999,'other','2029-01-01Z')
                """);
        jdbc.execute("select setval(pg_get_serial_sequence('employee_leave_policy_assignment','assignment_id'),1000)");
        jdbc.execute("select setval(pg_get_serial_sequence('leave_ledger','entry_id'),1000)");
    }

    @ParameterizedTest
    @CsvSource({"FULL,480","AM_HALF,240","PM_HALF,240"})
    void normalMinutesAndStoredCurrentBalance(String portion,int minutes) throws Exception {
        JsonNode result=preview(1,dates("2030-01-07",portion));
        assertThat(result.get("requestable").asBoolean()).isTrue();
        assertThat(result.get("validationStatus").asString()).isEqualTo("REQUESTABLE");
        assertThat(result.get("totalRequestedMinutes").asInt()).isEqualTo(minutes);
        assertThat(result.get("balanceDeductionMinutes").asInt()).isEqualTo(minutes);
        assertThat(result.get("remainingMinutes").asLong()).isEqualTo(1440);
        assertThat(result.get("dates").get(0).get("allocations").get(0).get("minutes").asInt()).isEqualTo(minutes);
        assertThat(OffsetDateTime.parse(result.get("asOf").asString()).toInstant()).isEqualTo(AS_OF.toInstant());
        verify(access,never()).isAdmin(any());
    }

    @Test
    void unsortedNonContinuousDatesUseOneHolidayRangeAndTotal1200() throws Exception {
        JsonNode result=preview(1,dates("2030-01-10","AM_HALF")+","+dates("2030-01-08","FULL")+","+dates("2030-01-07","FULL"));
        assertThat(result.get("requestable").asBoolean()).isTrue();
        assertThat(result.get("totalRequestedMinutes").asInt()).isEqualTo(1200);
        assertThat(result.get("dates").get(0).get("leaveDate").asString()).isEqualTo("2030-01-07");
        verify(holidays).findHolidays(LocalDate.of(2030,1,7),LocalDate.of(2030,1,10));
        verifyNoMoreInteractions(holidays);
    }

    @ParameterizedTest
    @ValueSource(strings={"FULL","AM_HALF"})
    void duplicateDateNeverCombinesTwoPortions(String portion) throws Exception {
        assertReason(preview(1,dates("2030-01-07",portion)+","+dates("2030-01-07","PM_HALF")),"DUPLICATE_DATE");
    }

    @ParameterizedTest
    @CsvSource({
            "SUBMITTING,FULL,AM_HALF,true,1","SUBMITTED,FULL,AM_HALF,true,1","APPROVED,AM_HALF,PM_HALF,true,1",
            "SUBMITTED,PM_HALF,AM_HALF,true,1","APPROVED,AM_HALF,FULL,true,1",
            "SUBMITTED,HOURLY,FULL,true,1","APPROVED,HOURLY,AM_HALF,true,1",
            "APPROVED,FULL,FULL,true,3",
            "DRAFT,FULL,FULL,false,1","REJECTED,FULL,FULL,false,1",
            "WITHDRAWN,FULL,FULL,false,1","CANCELLED,FULL,FULL,false,1"
    })
    void existingStatusPortionAndOtherTypeDateConflict(String status,String oldPortion,String newPortion,boolean conflict,int type) throws Exception {
        insertRequest(1,status,oldPortion,type);
        JsonNode result=preview(1,dates("2030-01-07",newPortion));
        assertThat(result.get("requestable").asBoolean()).isEqualTo(!conflict);
        if(conflict) { assertReason(result,"EXISTING_REQUEST"); }
    }

    @Test
    void otherEmployeeAndDifferentDatesDoNotConflict() throws Exception {
        insertRequest(2,"APPROVED","FULL",1);
        assertThat(preview(1,dates("2030-01-07","FULL")).get("requestable").asBoolean()).isTrue();
        insertRequest(1,"APPROVED","FULL",1);
        assertThat(preview(1,dates("2030-01-08","FULL")).get("requestable").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings={"LEGAL","COMPANY"})
    void bothHolidayTypesBlockWeekdayWithName(String type) throws Exception {
        when(holidays.findHolidays(any(),any())).thenReturn(new HolidayResult(true,List.of(
                new Holiday(LocalDate.of(2030,1,7),"휴일명",HolidayType.valueOf(type)))));
        JsonNode result=preview(1,dates("2030-01-07","FULL"));
        assertReason(result,"HOLIDAY");
        assertThat(result.get("reasons").get(0).get("message").asString()).contains("휴일명");
        assertThat(result.get("holidayVerification").asString()).isEqualTo("VERIFIED");
    }

    @Test
    void holidayOnUnrequestedGapDoesNotBlock() throws Exception {
        when(holidays.findHolidays(any(),any())).thenReturn(new HolidayResult(true,List.of(
                new Holiday(LocalDate.of(2030,1,8),"중간 휴일",HolidayType.LEGAL))));
        assertThat(preview(1,dates("2030-01-07","FULL")+","+dates("2030-01-09","FULL")).get("requestable").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings={"2030-01-12","2030-01-13"})
    void weekendBlocked(String date) throws Exception {
        assertReason(preview(1,dates(date,"FULL")),"WEEKEND");
    }

    @Test
    void holidayFailureIsUnverifiableAndDoesNotWrite() throws Exception {
        when(holidays.findHolidays(any(),any())).thenReturn(HolidayResult.unavailable());
        Map<String,List<Map<String,Object>>> before=rows();
        JsonNode result=preview(1,dates("2030-01-07","FULL"));
        assertThat(result.get("validationStatus").asString()).isEqualTo("UNVERIFIABLE");
        assertThat(result.get("holidayVerification").asString()).isEqualTo("UNAVAILABLE");
        assertReason(result,"HOLIDAY_SERVICE_UNAVAILABLE");
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void readonlySingleSnapshotAndNoWritesOrNPlusOne() throws Exception {
        Map<String,List<Map<String,Object>>> before=rows();
        preview(1,dates("2030-01-07","FULL")+","+dates("2030-01-09","FULL"));
        assertThat(SqlCapture.sql.get()).hasSize(7).allSatisfy(sql ->
                assertThat(sql).matches("(?s)^(select|with).*").doesNotContain("for update","for share"));
        assertThat(SqlCapture.readOnly.get()).containsOnly(true);
        assertThat(SqlCapture.isolation.get()).containsOnly(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(rows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings={
            "update leave_type set is_active=false where leave_type_id=1",
            "update leave_policy_type_rule set is_requestable=false where rule_id=1",
            "delete from leave_policy_type_rule where rule_id=1"
    })
    void unavailableTypeReturnsBusinessReason(String sql) throws Exception {
        jdbc.update(sql);
        assertReason(preview(1,dates("2030-01-07","FULL")),"TYPE_NOT_REQUESTABLE");
    }

    @ParameterizedTest
    @CsvSource({"DAY,FULL,FULL,true","DAY,FULL,AM_HALF,false","HALF_DAY,HALF,PM_HALF,true",
            "HALF_DAY,HALF,FULL,false","MIXED,MIXED,AM_HALF,true","HOUR,HOUR,FULL,false"})
    void masterAndPolicyAllowedUnits(String master,String allowed,String portion,boolean requestable) throws Exception {
        jdbc.update("update leave_type set request_unit=? where leave_type_id=1",master);
        jdbc.update("update leave_policy_type_rule set allowed_units=? where rule_id=1",allowed);
        JsonNode result=preview(1,dates("2030-01-07",portion));
        assertThat(result.get("requestable").asBoolean()).isEqualTo(requestable);
    }

    @ParameterizedTest
    @ValueSource(strings={
            "update leave_policy_type_rule set min_minutes=481 where rule_id=1",
            "update leave_policy_type_rule set max_minutes=479 where rule_id=1",
            "update leave_type set max_minutes=479 where leave_type_id=1"
    })
    void totalMinutesLimit(String sql) throws Exception {
        jdbc.update(sql);
        assertReason(preview(1,dates("2030-01-07","FULL")),"REQUEST_MINUTES_LIMIT");
    }

    @Test
    void masterLimitIsWholeRequestAndUnitMinutesIsEachDate() throws Exception {
        jdbc.update("update leave_type set max_minutes=500 where leave_type_id=1");
        assertReason(preview(1,dates("2030-01-07","AM_HALF")+","+dates("2030-01-08","FULL")),"REQUEST_MINUTES_LIMIT");
        jdbc.update("update leave_policy_version set unit_minutes=17 where policy_version_id=11");
        assertReason(preview(1,dates("2030-01-07","AM_HALF")),"UNIT_MINUTES_MISMATCH");
    }

    @Test
    void existingPolicyErrorsAndIncompatibleSettingsArePreserved() throws Exception {
        jdbc.update("update leave_policy_type_rule set allowed_units='HALF' where rule_id=1");
        jdbc.update("update leave_type set request_unit='DAY' where leave_type_id=1");
        perform(1,dates("2030-01-07","FULL")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_012"));
        jdbc.update("delete from employee_leave_policy_assignment where emp_id=1");
        perform(1,dates("2030-01-07","FULL")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LEAVE_010"));
        jdbc.update("insert into employee_leave_policy_assignment(emp_id,policy_id,effective_from) values(1,1,'2020-01-01Z')");
        jdbc.update("update leave_policy_group set active_policy_version_id=null where policy_id=1");
        perform(1,dates("2030-01-07","FULL")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LEAVE_011"));
    }

    @Test
    void futurePolicyAssignmentDoesNotChooseFuturePolicy() throws Exception {
        jdbc.update("update employee_leave_policy_assignment set effective_to='2030-01-08Z' where assignment_id=1");
        jdbc.update("insert into employee_leave_policy_assignment(emp_id,policy_id,effective_from) values(1,2,'2030-01-08Z')");
        assertThat(preview(1,dates("2030-01-10","FULL")).get("policyVersionId").asLong()).isEqualTo(11);
    }

    @Test
    void noneModeDoesNotQueryAnyAccountsOrGrantsAndStillCalculatesRequestedTime() throws Exception {
        JsonNode result=preview(2,dates("2030-01-07","FULL"));
        assertThat(result.get("requestable").asBoolean()).isTrue();
        assertThat(result.get("totalRequestedMinutes").asInt()).isEqualTo(480);
        assertThat(result.get("balanceDeductionMinutes").asInt()).isZero();
        assertThat(result.get("remainingMinutes").isNull()).isTrue();
        assertThat(SqlCapture.sql.get()).allSatisfy(sql -> assertThat(sql).doesNotContain("leave_account","leave_grant","leave_ledger"));
    }

    @ParameterizedTest
    @ValueSource(strings={"ACCOUNT","GRANT","LEDGER"})
    void noAccountGrantOrLedgerDoesNotUseOriginalMinutes(String missing) throws Exception {
        jdbc.update("delete from leave_ledger where account_id=1");
        if(!missing.equals("LEDGER")) { jdbc.update("delete from leave_grant where account_id=1"); }
        if(missing.equals("ACCOUNT")) { jdbc.update("delete from leave_account where account_id=1"); }
        JsonNode result=preview(1,dates("2030-01-07","FULL"));
        assertThat(result.get("remainingMinutes").asLong()).isZero();
        assertReason(result,"INSUFFICIENT_BALANCE");
    }

    @Test
    void currentReservationAndFutureCreatedAtBoundaryAreRespected() throws Exception {
        jdbc.update("""
                insert into leave_ledger(account_id,grant_id,event_type,reserved_delta,idempotency_key,created_at)
                values(1,100,'RESERVE',1200,'reserved',?)
                """,AS_OF);
        jdbc.update("""
                insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                values(1,100,'RESTORE',9999,'future',?)
                """,AS_OF.plusNanos(1000));
        JsonNode result=preview(1,dates("2030-01-07","FULL"));
        assertThat(result.get("remainingMinutes").asLong()).isEqualTo(240);
        assertReason(result,"INSUFFICIENT_BALANCE");
    }

    @Test
    void futureRecordedGrantCanCoverFutureDateButNotCurrentBalance() throws Exception {
        jdbc.update("update leave_grant set valid_from='2030-01-08T00:00:00+09:00' where grant_id=100");
        JsonNode future=preview(1,dates("2030-01-08","FULL"));
        assertThat(future.get("requestable").asBoolean()).isTrue();
        assertThat(future.get("remainingMinutes").asLong()).isZero();
        assertReason(preview(1,dates("2030-01-07","FULL")),"INSUFFICIENT_BALANCE");
    }

    @ParameterizedTest
    @ValueSource(strings={"2030-01-07T00:00:00+09:00","2030-01-07T12:00:00+09:00","2020-01-02T00:00:00Z"})
    void expiryBoundaryNeverBecomesSuccess(String expiry) throws Exception {
        jdbc.update("update leave_grant set expires_on=?::timestamptz where grant_id=100",expiry);
        JsonNode result=preview(1,dates("2030-01-07","FULL"));
        assertThat(result.get("requestable").asBoolean()).isFalse();
        assertThat(result.get("validationStatus").asString()).isEqualTo(expiry.contains("12:00")?"UNVERIFIABLE":"NOT_REQUESTABLE");
    }

    @ParameterizedTest
    @CsvSource({"-1,0","100,-1","100,101"})
    void grantAccountingInvalidityReturns409(int quantity,int reserved) throws Exception {
        jdbc.update("update leave_ledger set quantity_delta=?,reserved_delta=? where entry_id=100",quantity,reserved);
        perform(1,dates("2030-01-07","FULL")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    void pastDateBusinessFailureAndSeoulTodayBoundary() throws Exception {
        assertReason(preview(1,dates("2030-01-06","FULL")),"PAST_DATE");
        assertThat(preview(1,dates("2030-01-07","FULL")).get("requestable").asBoolean()).isTrue();
        verify(leavePreviewClock,times(2)).instant();
    }

    @Test
    void spoofedQueryAndHeadersCannotChangeEmployee() throws Exception {
        insertRequest(2,"APPROVED","FULL",1);
        mvc.perform(post(URL).param("employeeId","2").header("X-Employee-Id","2")
                        .contentType(MediaType.APPLICATION_JSON).content(payload(1,dates("2030-01-07","FULL"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requestable").value(true))
                .andExpect(jsonPath("$.data.remainingMinutes").value(1440));
    }

    @ParameterizedTest
    @ValueSource(strings={"UNAUTHORIZED","AUTHENTICATION_UNAVAILABLE"})
    void authenticationFailuresOccurBeforeExternalOrDb(String error) throws Exception {
        when(access.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.valueOf(error)));
        perform(1,dates("2030-01-07","FULL")).andExpect(error.equals("UNAUTHORIZED")?status().isUnauthorized():status().isServiceUnavailable());
        verifyNoInteractions(holidays);
        assertThat(SqlCapture.sql.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"ACTIVE","ASSIGNMENT","TYPE","INSERT","STATUS","RESERVE","CONSUME","RESTORE"})
    void concurrentChangesCannotMixSnapshot(String mutation) throws Exception {
        if(mutation.equals("STATUS")) { insertRequest(1,"DRAFT","FULL",1); }
        JsonNode before=preview(1,dates("2030-01-07","FULL"));
        SqlCapture.entered=new CountDownLatch(1); SqlCapture.release=new CountDownLatch(1);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> pending=executor.submit(()->{
                SqlCapture.sql.get().clear();
                SqlCapture.gated.set(true);
                try { return preview(1,dates("2030-01-07","FULL")); }
                finally { SqlCapture.gated.remove(); }
            });
            assertThat(SqlCapture.entered.await(10,TimeUnit.SECONDS)).isTrue();
            switch(mutation) {
                case "ACTIVE" -> jdbc.update("update leave_policy_group set active_policy_version_id=12 where policy_id=1");
                case "ASSIGNMENT" -> jdbc.update("update employee_leave_policy_assignment set policy_id=2 where assignment_id=1");
                case "TYPE" -> jdbc.update("update leave_type set is_active=false where leave_type_id=1");
                case "INSERT" -> insertRequest(1,"SUBMITTED","FULL",1);
                case "STATUS" -> jdbc.update("update leave_request set status='SUBMITTED'");
                case "RESERVE" -> jdbc.update("""
                        insert into leave_ledger(account_id,grant_id,event_type,reserved_delta,idempotency_key,created_at)
                        values(1,100,'RESERVE',1200,'concurrent','2029-02-01Z')
                        """);
                case "CONSUME" -> jdbc.update("""
                        insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                        values(1,100,'CONSUME',-1200,'concurrent','2029-02-01Z')
                        """);
                case "RESTORE" -> jdbc.update("""
                        insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                        values(1,100,'RESTORE',480,'concurrent','2029-02-01Z')
                        """);
            }
            SqlCapture.release.countDown();
            assertThat(pending.get(10,TimeUnit.SECONDS)).isEqualTo(before);
            assertThat(preview(1,dates("2030-01-07","FULL"))).isNotEqualTo(before);
        } finally {
            SqlCapture.release.countDown();
            executor.shutdownNow();
        }
    }

    private void insertRequest(long employee,String status,String portion,int type) {
        Long id=jdbc.queryForObject("""
                insert into leave_request(emp_id,policy_version_id,leave_type_id,status)
                values(?,11,?,case when ?='SUBMITTING' then 'DRAFT' else ? end) returning leave_req_id
                """,Long.class,employee,type,status,status);
        if (status.equals("SUBMITTING")) {
            jdbc.update("""
                    update leave_request set status='SUBMITTING',submission_key='preview-' || leave_req_id,
                        submission_payload_hash=repeat('a',64),approval_creation_status='UNKNOWN',
                        approval_request_id=?::uuid,approval_status_changed_at=now(),approval_attempt_count=1
                    where leave_req_id=?
                    """,java.util.UUID.randomUUID().toString(),id);
        }
        jdbc.update("""
                insert into leave_request_date(leave_req_id,leave_date,portion,requested_minutes,deduct_minutes)
                values(?,'2030-01-07T00:00:00+09:00',?,60,60)
                """,id,portion);
    }

    private static String dates(String date,String portion) {
        return "{\"leaveDate\":\""+date+"\",\"portion\":\""+portion+"\"}";
    }
    private static String payload(long type,String dates) {
        return "{\"leaveTypeId\":"+type+",\"dates\":["+dates+"]}";
    }
    private ResultActions perform(long type,String dates) throws Exception {
        return mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(payload(type,dates)));
    }
    private JsonNode preview(long type,String dates) throws Exception {
        MvcResult result=perform(type,dates).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
    }
    private void assertReason(JsonNode response,String code) {
        assertThat(response.get("requestable").asBoolean()).isFalse();
        List<String> codes=new ArrayList<>();
        for(JsonNode reason:response.get("reasons")) { codes.add(reason.get("code").asString()); }
        assertThat(codes).contains(code);
    }
    private Map<String,List<Map<String,Object>>> rows() {
        Map<String,List<Map<String,Object>>> rows=new LinkedHashMap<>();
        for(String table:TABLES) { rows.put(table,jdbc.queryForList("select *,xmin::text as xmin_value from "+table+" order by 1")); }
        return rows;
    }
}
