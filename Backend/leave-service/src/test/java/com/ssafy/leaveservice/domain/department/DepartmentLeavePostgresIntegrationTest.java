package com.ssafy.leaveservice.domain.department;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.client.hr.HrClient;
import com.ssafy.leaveservice.client.hr.HrClient.*;
import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.LeaveAccountBalance;
import com.ssafy.leaveservice.domain.department.service.DepartmentLeaveService;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "leave.test.database-owner=DepartmentLeavePostgresIntegrationTest",
        "spring.cloud.config.enabled=false","eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/","spring.jpa.open-in-view=false",
        "leave.hr.enabled=true","leave.hr.employee-info-uri=urn:test:employee",
        "leave.hr.department-members-uri=urn:test:members","leave.hr.duty-info-uri=urn:test:duty",
        "leave.hr.jwt-claims.department-id=test_dept","leave.hr.jwt-claims.duty-id=test_duty",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.ssafy.leaveservice.domain.department.DepartmentLeavePostgresIntegrationTest$SqlCapture"})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class DepartmentLeavePostgresIntegrationTest {

    @Autowired
    private PostgreSQLContainer postgres;

    public static class SqlCapture implements StatementInspector {
        static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        static final ThreadLocal<List<Boolean>> readOnly = ThreadLocal.withInitial(ArrayList::new);
        static final ThreadLocal<Boolean> gate = ThreadLocal.withInitial(() -> false);
        @Override
        public String inspect(String sql) {
            statements.get().add(sql.toLowerCase());
            readOnly.get().add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            if (gate.get() && sql.contains("with calculation_time")) {
                gate.remove();
                return sql.replace("where emp_id in (", "where department_test_gate() and emp_id in (");
            }
            return sql;
        }
    }

    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2030-01-01T00:00:00Z");
    private static final List<String> TABLES = List.of("leave_ledger","leave_request_allocation","leave_request_date",
            "leave_request","leave_grant","leave_accrual_rule","leave_policy_type_rule",
            "employee_leave_policy_assignment","leave_account","leave_type","leave_balance_type",
            "leave_policy_version","leave_policy_group");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired LeaveAccountRepository accounts;
    @Autowired DepartmentLeaveService departmentLeaveService;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean HrClient hrClient;
    @MockitoBean(name="leavePreviewClock") Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(AS_OF.toInstant());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(hrClient.isAvailable()).thenReturn(true);
        when(hrClient.findEmployee(10L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(SqlCapture.statements.get()).isEmpty();
            return new EmployeeInfo(10L,3L,7L,99L, null, EmploymentStatus.EMPLOYED);
        });
        when(hrClient.findOrganizationChain(10L)).thenReturn(new OrganizationChain(10L,3L,
                List.of(new OrganizationUnit(3L,"개발팀","TEAM",10L))));
        when(hrClient.findDuty(7L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new DutyInfo(7L,"팀장");
        });
        when(hrClient.findDepartmentMembers(3L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new MemberBatch(List.of(new Member(12L,3L, EmploymentStatus.EMPLOYED),new Member(10L,3L, EmploymentStatus.EMPLOYED),new Member(11L,3L, EmploymentStatus.EMPLOYED)),true);
        });
        jdbcTemplate.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : TABLES) {
            jdbcTemplate.update("delete from " + table);
        }
        jdbcTemplate.update("insert into leave_policy_group(policy_id,name) values(1,'정책')");
        jdbcTemplate.update("insert into leave_policy_version(policy_version_id,policy_id,version_no,base_type) values(1,1,1,'HIRE_DATE')");
        jdbcTemplate.update("insert into leave_balance_type(balance_type_id,name,is_active) values(1,'연차',false),(2,'보상',true)");
        jdbcTemplate.update("insert into leave_type(leave_type_id,balance_type_id,name,balance_mode,request_unit) values(1,1,'연차','ACCOUNT','DAY')");
        jdbcTemplate.update("insert into leave_account(account_id,emp_id,balance_type_id) values(1,10,1),(2,11,1),(3,10,2),(4,99,1)");
        jdbcTemplate.update("""
                insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from,expires_on)
                values(1,1,'MANUAL',99999,'2000-01-01Z',null),(2,2,'MANUAL',99999,'2000-01-01Z',null),
                      (3,3,'MANUAL',99999,'2000-01-01Z',null),(4,4,'MANUAL',99999,'2000-01-01Z',null),
                      (5,1,'MANUAL',99999,'2000-01-01Z','2030-01-01Z'),
                      (6,1,'MANUAL',99999,'2030-01-02Z',null)
                """);
        jdbcTemplate.update("""
                insert into leave_ledger(entry_id,account_id,grant_id,event_type,quantity_delta,reserved_delta,idempotency_key,created_at)
                values(1,1,1,'GRANT',480,0,'grant1','2000-01-01Z'),(2,1,1,'RESERVE',0,120,'reserve1','2000-01-01Z'),
                      (3,2,2,'GRANT',960,0,'grant2','2000-01-01Z'),(4,4,4,'GRANT',777,0,'other','2000-01-01Z'),
                      (5,1,5,'GRANT',9999,0,'expired','2000-01-01Z'),(6,1,6,'GRANT',9999,0,'future','2000-01-01Z')
                """);
        jdbcTemplate.update("""
                insert into leave_request(leave_req_id,emp_id,policy_version_id,leave_type_id,status,total_minutes,created_at)
                values(1,10,1,1,'APPROVED',480,'2020-01-01Z'),(2,11,1,1,'REJECTED',480,'2029-01-01Z'),
                      (3,99,1,1,'SUBMITTED',480,'2029-12-01Z'),(4,10,1,1,'CANCELLED',480,'2029-01-01Z')
                """);
        SqlCapture.statements.get().clear();
        SqlCapture.readOnly.get().clear();
    }

    @Test
    void requestsUseAuthenticatedEmployeeForHrAndSingleScopedSortedSelect() throws Exception {
        Map<String,List<Map<String,Object>>> before = rows();
        mvc.perform(get(url("requests")).with(user("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].requestId").value(1))
                .andExpect(jsonPath("$.data[0].employeeId").value(10));
        verify(hrClient).findEmployee(10L);
        verify(hrClient,never()).findDuty(any());
        verify(hrClient).findDepartmentMembers(3L);
        assertSingleRead();
        assertThat(SqlCapture.statements.get().getFirst()).contains("emp_id in");
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void balancesReuseAmountsAndIncludeInactiveTypesNoLedgerAndNoAccountMembers() throws Exception {
        Map<String,List<Map<String,Object>>> before = rows();
        mvc.perform(get(url("balances")).with(user("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.departmentId").value(3))
                .andExpect(jsonPath("$.data.asOf").value("2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.employees.length()").value(3))
                .andExpect(jsonPath("$.data.employees[0].employeeId").value(10))
                .andExpect(jsonPath("$.data.employees[0].balances[0].quantityMinutes").value(480))
                .andExpect(jsonPath("$.data.employees[0].balances[0].reservedMinutes").value(120))
                .andExpect(jsonPath("$.data.employees[0].balances[0].remainingMinutes").value(360))
                .andExpect(jsonPath("$.data.employees[0].balances[1].quantityMinutes").value(0))
                .andExpect(jsonPath("$.data.employees[1].employeeId").value(11))
                .andExpect(jsonPath("$.data.employees[1].balances[0].quantityMinutes").value(960))
                .andExpect(jsonPath("$.data.employees[2].employeeId").value(12))
                .andExpect(jsonPath("$.data.employees[2].balances").isEmpty());
        assertSingleRead();
        verify(clock,times(1)).instant();
        assertThat(rows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings={"팀원","부장","사원","일반 관리자"," 휴가 관리자","휴가 관리자 "})
    void highPositionAndSystemAdminCannotReplaceExactDuty(String dutyName) throws Exception {
        when(hrClient.findDuty(7L)).thenReturn(new DutyInfo(7L,dutyName));
        mvc.perform(get(url("balances")).with(user("ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        verify(hrClient,never()).findDepartmentMembers(any());
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"EMPLOYEE","DUTY","MEMBERS","AUTH_FAILURE","TIMEOUT"})
    void hrFailuresAre503Not403OrEmptySuccess(String step) throws Exception {
        if (step.equals("EMPLOYEE") || step.equals("AUTH_FAILURE")) {
            when(hrClient.findEmployee(10L)).thenThrow(new IllegalStateException("remote failure"));
        } else if (step.equals("DUTY")) {
            when(hrClient.findDuty(7L)).thenThrow(new IllegalStateException("HTTP 500"));
        } else {
            when(hrClient.findDepartmentMembers(3L)).thenThrow(new IllegalStateException("timeout"));
        }
        unavailable("balances");
    }

    @ParameterizedTest
    @ValueSource(strings={"NULL","LIST_NULL","DUPLICATE","OTHER_DEPT","NULL_MEMBER","NULL_ID","NEGATIVE_ID","NULL_DEPT","INCOMPLETE"})
    void malformedMembershipFailsWholeRequestBeforeDb(String issue) throws Exception {
        MemberBatch batch = switch (issue) {
            case "NULL" -> null;
            case "LIST_NULL" -> new MemberBatch(null,true);
            case "DUPLICATE" -> new MemberBatch(List.of(new Member(10L,3L, EmploymentStatus.EMPLOYED),new Member(10L,3L, EmploymentStatus.EMPLOYED)),true);
            case "OTHER_DEPT" -> new MemberBatch(List.of(new Member(10L,3L, EmploymentStatus.EMPLOYED),new Member(99L,5L, EmploymentStatus.EMPLOYED)),true);
            case "NULL_MEMBER" -> new MemberBatch(Arrays.asList((Member)null),true);
            case "NULL_ID" -> new MemberBatch(List.of(new Member(null,3L, EmploymentStatus.EMPLOYED)),true);
            case "NEGATIVE_ID" -> new MemberBatch(List.of(new Member(-1L,3L, EmploymentStatus.EMPLOYED)),true);
            case "NULL_DEPT" -> new MemberBatch(List.of(new Member(10L,null, EmploymentStatus.EMPLOYED)),true);
            default -> new MemberBatch(List.of(new Member(10L,3L, EmploymentStatus.EMPLOYED)),false);
        };
        when(hrClient.findDepartmentMembers(3L)).thenReturn(batch);
        unavailable("requests");
    }

    @ParameterizedTest
    @ValueSource(strings={"DEPARTMENT","DUTY","STATUS"})
    void staleOrganizationClaimsDenyWithoutUsingOldDuty(String change) throws Exception {
        when(hrClient.findEmployee(10L)).thenReturn(new EmployeeInfo(10L,change.equals("DEPARTMENT")?5L:3L,
                change.equals("DUTY")?8L:7L,99L, null, change.equals("STATUS") ? EmploymentStatus.ON_LEAVE : EmploymentStatus.EMPLOYED));
        mvc.perform(get(url("balances")).with(user("USER"))).andExpect(status().isForbidden());
        verify(hrClient,never()).findDuty(any());
        verify(hrClient,never()).findDepartmentMembers(any());
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"NULL_EMPLOYEE","WRONG_EMPLOYEE","NULL_DEPT","NULL_DUTY","WRONG_DUTY_RESPONSE","BLANK_NAME"})
    void invalidEmployeeAndDutyResponsesAreUnavailable(String issue) throws Exception {
        switch (issue) {
            case "NULL_EMPLOYEE" -> when(hrClient.findEmployee(10L)).thenReturn(null);
            case "WRONG_EMPLOYEE" -> when(hrClient.findEmployee(10L)).thenReturn(new EmployeeInfo(99L,3L,7L,99L, null, EmploymentStatus.EMPLOYED));
            case "NULL_DEPT" -> when(hrClient.findEmployee(10L)).thenReturn(new EmployeeInfo(10L,null,7L,99L, null, EmploymentStatus.EMPLOYED));
            case "NULL_DUTY" -> when(hrClient.findEmployee(10L)).thenReturn(new EmployeeInfo(10L,3L,null,99L, null, EmploymentStatus.EMPLOYED));
            case "WRONG_DUTY_RESPONSE" -> when(hrClient.findDuty(7L)).thenReturn(new DutyInfo(9L,"팀장"));
            default -> when(hrClient.findDuty(7L)).thenReturn(new DutyInfo(7L," "));
        }
        unavailable("balances");
    }

    @ParameterizedTest
    @ValueSource(strings={"requests","balances"})
    void trustedEmptyMembershipReturnsEmptyWithoutSql(String endpoint) throws Exception {
        when(hrClient.findDepartmentMembers(3L)).thenReturn(new MemberBatch(List.of(),true));
        mvc.perform(get(url(endpoint)).with(user("USER"))).andExpect(status().isOk())
                .andExpect(jsonPath(endpoint.equals("requests")?"$.data":"$.data.employees").isEmpty());
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @Test
    void otherDepartmentCannotCallMemberEndpointOrReadSql() throws Exception {
        mvc.perform(get("/api/v1/leaves/departments/5/requests").with(user("ADMIN"))).andExpect(status().isForbidden());
        verify(hrClient,never()).findDepartmentMembers(any());
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @Test
    void anonymousAndForgedHeadersCannotAuthenticate() throws Exception {
        mvc.perform(get(url("requests")).header("X-Employee-Id","10").param("dutyId","7"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(hrClient);
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"test_dept","test_duty","sub"})
    void missingRequiredClaimCannotBeFilledByQueryOrHr(String key) throws Exception {
        mvc.perform(get(url("balances")).with(SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(jwt -> jwt.subject("10").claim("test_dept",3L).claim("test_duty",7L)
                                .claim("test_position",99L).claims(claims -> claims.remove(key))))
                        .param("deptId","3").param("dutyId","7"))
                .andExpect(status().isUnauthorized());
        if (!key.equals("test_duty")) {
            verify(hrClient,never()).findEmployee(any());
        }
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings={"NEGATIVE_Q","NEGATIVE_R","OVER_RESERVED","GRANT_OFFSET"})
    void integrityFailureCannotBeOffsetByAnotherGrant(String issue) throws Exception {
        switch (issue) {
            case "NEGATIVE_Q" -> jdbcTemplate.update("update leave_ledger set quantity_delta=-1 where entry_id=1");
            case "NEGATIVE_R" -> jdbcTemplate.update("update leave_ledger set reserved_delta=-1 where entry_id=2");
            case "OVER_RESERVED" -> jdbcTemplate.update("update leave_ledger set reserved_delta=481 where entry_id=2");
            default -> {
                jdbcTemplate.update("update leave_ledger set quantity_delta=-1 where entry_id=1");
                jdbcTemplate.update("update leave_grant set valid_from='2000-01-01Z' where grant_id=6");
            }
        }
        mvc.perform(get(url("balances")).with(user("USER"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    void sqlTimeMatchesResponseAndUsesInclusiveLedgerBoundary() throws Exception {
        jdbcTemplate.update("update leave_ledger set created_at=? where entry_id=1",AS_OF);
        jdbcTemplate.update("update leave_ledger set created_at=? where entry_id=2",AS_OF.plusNanos(1000));
        mvc.perform(get(url("balances")).with(user("USER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asOf").value("2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.employees[0].balances[0].quantityMinutes").value(480))
                .andExpect(jsonPath("$.data.employees[0].balances[0].reservedMinutes").value(0));
    }

    @Test
    void batchResultsEqualOriginalSingleEmployeeCalculation() throws Exception {
        mvc.perform(get(url("balances")).with(user("USER"))).andExpect(status().isOk());
        List<LeaveAccountBalance> single = accounts.findBalancesByEmployeeId(10L,AS_OF);
        assertThat(single.getFirst().getQuantityMinutes().longValueExact()).isEqualTo(480);
        assertThat(single.getFirst().getReservedMinutes().longValueExact()).isEqualTo(120);
        assertThat(single.get(1).getQuantityMinutes().longValueExact()).isZero();
    }

    @Test
    void outerTransactionCannotEncloseHrCalls() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> departmentLeaveService.getRequests(3L)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(hrClient);
    }

    @Test
    void largeDepartmentStillUsesOneSelect() throws Exception {
        List<Member> members = new ArrayList<>();
        members.add(new Member(10L,3L, EmploymentStatus.EMPLOYED));
        members.add(new Member(11L,3L, EmploymentStatus.EMPLOYED));
        for (long employeeId = 1000; employeeId < 2000; employeeId++) {
            members.add(new Member(employeeId,3L, EmploymentStatus.EMPLOYED));
        }
        when(hrClient.findDepartmentMembers(3L)).thenReturn(new MemberBatch(members,true));
        mvc.perform(get(url("balances")).with(user("USER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employees.length()").value(1002));
        assertSingleRead();
    }

    @ParameterizedTest
    @ValueSource(strings={"DRAFT","SUBMITTING","SUBMITTED","APPROVED","REJECTED","WITHDRAWN","CANCELLED"})
    void everyStoredStatusIsReturnedWithoutPolicyOrStatusFiltering(String state) throws Exception {
        if (state.equals("SUBMITTING")) {
            jdbcTemplate.update("""
                    update leave_request set status='SUBMITTING',submission_key='dept-test',
                        submission_payload_hash=repeat('a',64),approval_creation_status='NOT_SENT',
                        approval_request_id='11111111-1111-1111-1111-111111111111',
                        approval_status_changed_at=clock_timestamp(),approval_attempt_count=0 where leave_req_id=1
                    """);
        } else {
            jdbcTemplate.update("update leave_request set status=? where leave_req_id=1",state);
        }
        mvc.perform(get(url("requests")).with(user("USER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(state.equals("APPROVED") ? 1 : 0));
        assertSingleRead();
    }

    @Test
    void noGrantAndIntBoundaryAmountsDoNotUseOriginalMinutesOrOverflowIntSum() throws Exception {
        jdbcTemplate.update("delete from leave_ledger where grant_id=3");
        jdbcTemplate.update("delete from leave_grant where grant_id=3");
        jdbcTemplate.update("update leave_ledger set quantity_delta=2147483647 where entry_id=1");
        jdbcTemplate.update("""
                insert into leave_ledger(entry_id,account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                values(8,1,1,'ADJUST',2147483647,'large-int','2000-01-01Z')
                """);
        mvc.perform(get(url("balances")).with(user("USER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employees[0].balances[0].quantityMinutes").value(4294967294L))
                .andExpect(jsonPath("$.data.employees[0].balances[1].quantityMinutes").value(0));
        assertSingleRead();
    }

    @Test
    void oneStatementSnapshotExcludesLedgerInsertedWhileSelectWaits() throws Exception {
        jdbcTemplate.execute("""
                create or replace function department_test_gate() returns boolean language plpgsql volatile as $$
                begin perform pg_advisory_xact_lock_shared(880001); return true; end $$
                """);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection writer = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
            writer.setAutoCommit(false);
            writer.createStatement().execute("select pg_advisory_xact_lock(880001)");
            java.util.concurrent.Future<?> pending = executor.submit(() -> {
                SqlCapture.gate.set(true);
                try {
                    mvc.perform(get(url("balances")).with(user("USER"))).andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.employees[0].balances[0].quantityMinutes").value(480));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                } finally {
                    SqlCapture.gate.remove();
                }
            });
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                jdbcTemplate.execute("select pg_stat_clear_snapshot()");
                Integer waiters = jdbcTemplate.queryForObject("""
                        select count(*) from pg_stat_activity where wait_event='advisory'
                            and query like '%grant_totals%' and pid<>pg_backend_pid()
                        """,Integer.class);
                if (waiters != null && waiters > 0) {
                    waiting = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(waiting).isTrue();
            writer.createStatement().executeUpdate("""
                    insert into leave_ledger(entry_id,account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                    values(99,1,1,'ADJUST',100,'concurrent-department','2000-01-01Z')
                    """);
            writer.commit();
            pending.get(15,java.util.concurrent.TimeUnit.SECONDS);
            mvc.perform(get(url("balances")).with(user("USER"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.employees[0].balances[0].quantityMinutes").value(580));
        } finally {
            executor.shutdownNow();
            jdbcTemplate.execute("drop function if exists department_test_gate()");
        }
    }
    private void unavailable(String endpoint) throws Exception {
        mvc.perform(get(url(endpoint)).with(user("USER"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.error.code").value("LEAVE_017"));
        assertThat(SqlCapture.statements.get()).isEmpty();
    }

    private void assertSingleRead() {
        assertThat(SqlCapture.statements.get()).hasSize(1);
        assertThat(SqlCapture.readOnly.get()).containsExactly(true);
        assertThat(SqlCapture.statements.get().getFirst()).doesNotContain("for update");
    }

    private RequestPostProcessor user(String role) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt.subject("10").claim("role",role)
                        .claim("type","access").claim("test_dept",3L).claim("test_duty",7L).claim("test_position",99L))
                .authorities(new SimpleGrantedAuthority("ROLE_"+role));
    }

    private String url(String endpoint) {
        return "/api/v1/leaves/departments/3/" + endpoint;
    }

    private Map<String,List<Map<String,Object>>> rows() {
        Map<String,List<Map<String,Object>>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            rows.put(table,jdbcTemplate.queryForList("select *,xmin::text as row_version from " + table + " order by 1"));
        }
        return rows;
    }
}
