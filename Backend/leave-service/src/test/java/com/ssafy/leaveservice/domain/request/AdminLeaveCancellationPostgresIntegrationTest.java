package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.client.approval.FakeApprovalWithdrawalClientConfiguration;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalCommand;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalResult;
import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentRequest;
import com.ssafy.leaveservice.domain.adjustment.service.LeaveAdjustmentService;
import com.ssafy.leaveservice.domain.request.dto.AdminCancellationAttempt;
import com.ssafy.leaveservice.domain.request.dto.AdminLeaveCancellationRequest;
import com.ssafy.leaveservice.domain.request.dto.AdminLeaveCancellationResponse;
import com.ssafy.leaveservice.domain.request.entity.AdminCancellationStatus;
import com.ssafy.leaveservice.domain.request.service.AdminLeaveCancellationService;
import com.ssafy.leaveservice.domain.request.service.AdminLeaveCancellationTransactionService;
import com.ssafy.leaveservice.domain.request.service.LeaveApprovalResultService;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.ssafy.leaveservice.support.TestTokens;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"leave.test.database-owner=AdminLeaveCancellationPostgresIntegrationTest",
        "leave.hr.enabled=true","leave.hr.jwt-claims.duty-id=dutyId",
        "leave.approval.withdrawal.enabled=true", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/", "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, FakeApprovalWithdrawalClientConfiguration.class})
@AutoConfigureMockMvc
class AdminLeaveCancellationPostgresIntegrationTest {
    private static final String URL = "/api/v1/leaves/admin/requests/100/cancel";
    private static final String REASON = " 관리자 직권 취소 ";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PostgreSQLContainer postgres;
    @Autowired AdminLeaveCancellationService service;
    @Autowired AdminLeaveCancellationTransactionService transactions;
    @Autowired LeaveAdjustmentService adjustments;
    @Autowired LeaveApprovalResultService approvalResults;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired FakeApprovalWithdrawalClientConfiguration.FakeApprovalWithdrawalClient client;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.ssafy.leaveservice.client.hr.HrClient hrClient;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(hrClient.isAvailable()).thenReturn(true);
        org.mockito.Mockito.when(hrClient.findEmployee(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new com.ssafy.leaveservice.client.hr.HrClient.EmployeeInfo(id,1L,id,null,null,
                    com.ssafy.leaveservice.client.hr.HrClient.EmploymentStatus.EMPLOYED);
        });
        org.mockito.Mockito.when(hrClient.findDuty(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
                new com.ssafy.leaveservice.client.hr.HrClient.DutyInfo(call.getArgument(0),"휴가관리자"));

        TestTokens.authenticate(10, "ADMIN");
        client.reset();
        jdbc.update("update leave_policy_group set active_policy_version_id=null");
        for (String table : List.of("leave_ledger", "leave_request_allocation", "leave_request_date", "leave_request",
                "leave_grant", "leave_accrual_rule", "leave_policy_type_rule", "employee_leave_policy_assignment",
                "leave_account", "leave_type", "leave_balance_type", "leave_policy_version", "leave_policy_group")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("insert into leave_policy_group(policy_id,name) values(1,'정책')");
        jdbc.update("insert into leave_policy_version(policy_version_id,policy_id,version_no,base_type) values(1,1,1,'HIRE_DATE')");
        jdbc.update("insert into leave_balance_type(balance_type_id,name) values(1,'연차')");
        jdbc.update("insert into leave_type(leave_type_id,balance_type_id,name,balance_mode) values(1,1,'연차','ACCOUNT'),(2,null,'비차감','NONE')");
        jdbc.update("insert into leave_account(account_id,emp_id,balance_type_id) values(901,42,1),(902,43,1)");
        jdbc.update("""
                insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from)
                values(1001,901,'MANUAL',99999,'2000-01-01Z'),(1002,901,'MANUAL',99999,'2000-01-01Z'),
                      (2001,902,'MANUAL',99999,'2000-01-01Z')
                """);
        jdbc.update("""
                insert into leave_request(leave_req_id,emp_id,policy_version_id,leave_type_id,status,approval_doc_id,
                    total_minutes,total_deduct_minutes,reason,submission_key,submission_payload_hash,approval_creation_status,
                    approval_request_id,approval_status_changed_at,approval_attempt_count)
                values(100,42,1,1,'SUBMITTED',9001,960,960,'원래 신청 사유','submit-100',repeat('a',64),'LINKED',
                    '00000000-0000-0000-0000-000000000100',now(),1),
                    (200,43,1,2,'SUBMITTED',9002,480,0,'비차감 신청','submit-200',repeat('b',64),'LINKED',
                    '00000000-0000-0000-0000-000000000200',now(),1)
                """);
        jdbc.update("""
                insert into leave_request_date(request_date_id,leave_req_id,leave_date,portion,requested_minutes,deduct_minutes)
                values(101,100,'2030-01-07T00:00:00+09:00','FULL',480,480),
                      (102,100,'2030-01-08T00:00:00+09:00','FULL',480,480),
                      (201,200,'2030-01-09T00:00:00+09:00','FULL',480,0)
                """);
        jdbc.update("""
                insert into leave_request_allocation(allocation_id,leave_req_id,request_date_id,emp_id,account_id,grant_id,allocated_minutes)
                values(1,100,101,42,901,1001,240),(2,100,101,42,901,1002,240),(3,100,102,42,901,1002,480)
                """);
        jdbc.update("""
                insert into leave_ledger(entry_id,account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                values(11,901,1001,'GRANT',480,'grant-1','2000-01-01Z'),
                      (12,901,1002,'GRANT',1440,'grant-2','2000-01-01Z'),
                      (13,902,2001,'GRANT',480,'grant-other','2000-01-01Z')
                """);
        jdbc.update("""
                insert into leave_ledger(entry_id,account_id,grant_id,leave_req_id,allocation_id,event_type,reserved_delta,idempotency_key,created_at)
                values(21,901,1001,100,1,'RESERVE',240,'reserve-1','2020-01-01Z'),
                      (22,901,1002,100,2,'RESERVE',240,'reserve-2','2020-01-01Z'),
                      (23,901,1002,100,3,'RESERVE',480,'reserve-3','2020-01-01Z')
                """);
        jdbc.execute("select setval(pg_get_serial_sequence('leave_ledger','entry_id'),1000)");
    }

    @AfterEach
    void clearAuthentication() {
        TestTokens.clear();
    }

    @Test
    void publicApiCancelsAllAllocationsAndPreservesOriginalDataAndCreationHistory() throws Exception {
        List<Map<String, Object>> originalLedger = jdbc.queryForList("select *,xmin::text as version from leave_ledger order by entry_id");
        List<Map<String, Object>> allocations = rows("leave_request_allocation");
        List<Map<String, Object>> dates = rows("leave_request_date");
        List<Map<String, Object>> grants = rows("leave_grant");
        mvc.perform(post(URL).with(admin(10)).contentType("application/json").content(body(REASON)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").isEmpty()).andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.approvalCreationStatus").value("LINKED"))
                .andExpect(jsonPath("$.data.approvalDocId").value(9001))
                .andExpect(jsonPath("$.data.adminCancelStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.cancelledBy").value(10)).andExpect(jsonPath("$.data.cancelReason").value(REASON));
        assertThat(client.calls).hasValue(1);
        assertThat(releaseCount()).isEqualTo(3);
        assertThat(reserved()).isZero();
        assertThat(jdbc.queryForList("select *,xmin::text as version from leave_ledger where entry_id<1000 order by entry_id")).isEqualTo(originalLedger);
        assertThat(rows("leave_request_allocation")).isEqualTo(allocations);
        assertThat(rows("leave_request_date")).isEqualTo(dates);
        assertThat(rows("leave_grant")).isEqualTo(grants);
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where event_type='RELEASE' and (quantity_delta<>0 or actor_emp_id is not null)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select sum(reserved_delta) from leave_ledger where event_type='RELEASE'", Long.class)).isEqualTo(-960);
        assertThat(jdbc.queryForObject("select count(*) from leave_ledger where idempotency_key like 'leave:admin-cancel:request:100:allocation:%'", Integer.class)).isEqualTo(3);
        mvc.perform(get("/api/v1/leaves/admin/requests/100").with(admin(10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.adminCancelStatus").doesNotExist());
        mvc.perform(get("/api/v1/leaves/admin/employees/42/balances").with(admin(10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].reservedMinutes").value(0))
                .andExpect(jsonPath("$.data[0].quantityMinutes").value(1920));
    }

    @Test
    void noneTypeChangesOnlyRequestAndStillPreservesCancellationAudit() {
        List<Map<String, Object>> before = rows("leave_ledger");
        AdminLeaveCancellationResponse response = service.cancel(200L, input(REASON));
        assertThat(response.status().name()).isEqualTo("CANCELLED");
        assertThat(response.cancelledBy()).isEqualTo(10);
        assertThat(rows("leave_ledger")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED", "WITHDRAWN", "CANCELLED", "DRAFT", "SUBMITTING", "UNKNOWN", "IN_PROGRESS", "LEGACY"})
    void rejectsUnsupportedOriginalStatusesBeforeCallingApproval(String scenario) {
        if (List.of("DRAFT", "SUBMITTING", "UNKNOWN", "IN_PROGRESS").contains(scenario)) {
            String status = scenario.equals("DRAFT") ? "DRAFT" : "SUBMITTING";
            String creation = List.of("UNKNOWN", "IN_PROGRESS").contains(scenario) ? scenario : "NOT_SENT";
            jdbc.update("update leave_request set status=?,approval_creation_status=?,approval_doc_id=null where leave_req_id=100", status, creation);
        } else if (scenario.equals("LEGACY")) {
            jdbc.update("""
                    update leave_request set submission_key=null,submission_payload_hash=null,approval_creation_status=null,
                        approval_request_id=null,approval_status_changed_at=null,approval_attempt_count=null where leave_req_id=100
                    """);
        } else {
            jdbc.update("update leave_request set status=? where leave_req_id=100", scenario);
        }
        assertBusiness(() -> cancel(), ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED);
        assertThat(client.calls).hasValue(0);
        assertThat(releaseCount()).isZero();
        assertThat(request().get("admin_cancel_status")).isNull();
    }

    @Test
    void t2FailedCancellationIsNotAdminCancellationReplay() {
        jdbc.update("update leave_request set status='CANCELLED',approval_creation_status='FAILED',approval_doc_id=null where leave_req_id=100");
        assertBusiness(() -> cancel(), ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED);
        assertThat(client.calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{\"reason\":1}",
            "{\"reason\":\"취소\",\"administratorId\":999}", "{\"reason\":\"취소\",\"dates\":[]}"})
    void invalidBodyReturns400WithoutAnyWrites(String body) throws Exception {
        Map<String, Object> before = request();
        mvc.perform(post(URL).with(admin(10)).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(request()).isEqualTo(before);
        assertThat(client.calls).hasValue(0);
    }

    @Test
    void preserves2000CharacterReasonInRequestAndKeepsLedgerReasonWithin500() throws Exception {
        String reason = " " + "가".repeat(1998) + " ";
        AdminLeaveCancellationResponse response = service.cancel(100L, input(reason));
        assertThat(response.cancelReason()).isEqualTo(reason);
        assertThat(request().get("admin_cancel_reason")).isEqualTo(reason);
        assertThat(jdbc.queryForObject("select max(length(reason)) from leave_ledger where event_type='RELEASE'", Integer.class)).isLessThanOrEqualTo(500);
        mvc.perform(post(URL).with(admin(10)).contentType("application/json").content(body("가".repeat(2001))))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", "9223372036854775808"})
    void invalidRequestIdReturns400(String id) throws Exception {
        mvc.perform(post(URL.replace("100", id)).with(admin(10)).contentType("application/json").content(body(REASON)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingRequestReturns404() throws Exception {
        mvc.perform(post(URL.replace("100", "999")).with(admin(10)).contentType("application/json").content(body(REASON)))
                .andExpect(status().isNotFound());
    }

    @Test
    void roleAdminIsRequiredAndActorCannotComeFromHeaders() throws Exception {
        mvc.perform(post(URL).with(anonymous()).contentType("application/json").content(body(REASON)))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.when(hrClient.findDuty(10L)).thenReturn(
                new com.ssafy.leaveservice.client.hr.HrClient.DutyInfo(10L,"팀원"));
        mvc.perform(post(URL).with(jwt().jwt(j -> j.subject("10").claim("role", "USER").claim("type", "access").claim("dutyId",10L))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType("application/json").content(body(REASON)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        org.mockito.Mockito.when(hrClient.findDuty(10L)).thenReturn(
                new com.ssafy.leaveservice.client.hr.HrClient.DutyInfo(10L,"휴가관리자"));
        mvc.perform(post(URL).with(admin(10)).header("X-Employee-Id", "999").contentType("application/json").content(body(REASON)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cancelledBy").value(10));
    }

    @Test
    void markerIsCommittedAndEmployeeLockReleasedBeforeExternalCall() {
        client.behavior = command -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select pg_try_advisory_xact_lock(-42::bigint)", Boolean.class)).isTrue();
            assertThat(request().get("admin_cancel_status")).isEqualTo("IN_PROGRESS");
            assertThat(request().get("cancelled_at")).isNull();
            assertThat(reserved()).isEqualTo(960);
            assertThat(command.administratorId()).isEqualTo(10);
            assertThat(command.employeeId()).isEqualTo(42);
            assertThat(command.idempotencyKey()).isEqualTo("leave:admin-cancel:request:100");
            return confirmed(command);
        };
        assertThat(cancel().adminCancelStatus()).isEqualTo(AdminCancellationStatus.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT", "UNKNOWN", "ACCEPTED", "NULL", "WRONG_UUID", "WRONG_DOCUMENT", "NULL_OUTCOME"})
    void uncertainResultsNeverReleaseOrCancelAndPreserveInitialIntent(String scenario) throws Exception {
        client.behavior = command -> switch (scenario) {
            case "TIMEOUT" -> throw new IllegalStateException("timeout or lost response");
            case "NULL" -> null;
            case "WRONG_UUID" -> new ApprovalWithdrawalResult(UUID.randomUUID(), command.documentId(), ApprovalWithdrawalResult.Outcome.WITHDRAWN_CONFIRMED);
            case "WRONG_DOCUMENT" -> new ApprovalWithdrawalResult(command.withdrawalRequestId(), 999L, ApprovalWithdrawalResult.Outcome.WITHDRAWN_CONFIRMED);
            case "NULL_OUTCOME" -> new ApprovalWithdrawalResult(command.withdrawalRequestId(), command.documentId(), null);
            default -> new ApprovalWithdrawalResult(command.withdrawalRequestId(), command.documentId(), ApprovalWithdrawalResult.Outcome.valueOf(scenario));
        };
        mvc.perform(post(URL).with(admin(10)).contentType("application/json").content(body(REASON)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.adminCancelStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.cancelledAt").isEmpty());
        UUID operation = (UUID) request().get("admin_cancel_request_id");
        assertThat(reserved()).isEqualTo(960);
        assertThat(releaseCount()).isZero();
        assertThat(request().get("cancelled_by")).isNull();
        TestTokens.authenticate(10, "ADMIN");
        service.cancel(100L, input(REASON));
        assertThat(client.calls).hasValue(1);
        assertThat(client.lookups).hasValue(1);
        assertThat(request().get("admin_cancel_request_id")).isEqualTo(operation);
        assertThat(request().get("admin_cancel_requested_by")).isEqualTo(10L);
        assertThat(request().get("admin_cancel_reason")).isEqualTo(REASON);
    }

    @Test
    void confirmedNotWithdrawnIsFailedAndNeverAutomaticallyRetried() {
        client.behavior = command -> new ApprovalWithdrawalResult(command.withdrawalRequestId(), command.documentId(),
                ApprovalWithdrawalResult.Outcome.NOT_WITHDRAWN_CONFIRMED);
        assertBusiness(() -> cancel(), ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED);
        Map<String, Object> before = request();
        assertThat(before.get("admin_cancel_status")).isEqualTo("FAILED");
        assertThat(before.get("status")).isEqualTo("SUBMITTED");
        assertBusiness(() -> cancel(), ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED);
        assertThat(request()).isEqualTo(before);
        assertThat(client.calls).hasValue(1);
        assertThat(client.lookups).hasValue(0);
        assertThat(reserved()).isEqualTo(960);
    }

    @Test
    void completedRetryReturnsSameAuditWithoutRemoteCallsOrNewLedger() {
        AdminLeaveCancellationResponse first = cancel();
        List<Map<String, Object>> before = rows("leave_ledger");
        assertThat(cancel()).isEqualTo(first);
        assertThat(rows("leave_ledger")).isEqualTo(before);
        assertThat(client.calls).hasValue(1);
        assertThat(client.lookups).hasValue(0);
        assertBusiness(() -> service.cancel(100L, input("다른 사유")), ErrorCode.LEAVE_CANCELLATION_CONFLICT);
        TestTokens.authenticate(11, "ADMIN");
        assertBusiness(() -> cancel(), ErrorCode.LEAVE_CANCELLATION_CONFLICT);
        assertThat(request().get("cancelled_by")).isEqualTo(10L);
    }

    @Test
    void serverRestartAfterStartUsesOnlyExistingUuidLookup() {
        AdminCancellationAttempt started = transactions.start(100L, REASON);
        UUID operation = started.command().withdrawalRequestId();
        client.lookup = id -> confirmed(started.command());
        assertThat(cancel().adminCancelStatus()).isEqualTo(AdminCancellationStatus.COMPLETED);
        assertThat(client.calls).hasValue(0);
        assertThat(client.lookups).hasValue(1);
        assertThat(request().get("admin_cancel_request_id")).isEqualTo(operation);
    }

    @Test
    void unknownLookupCanLaterConfirmWithdrawalWithoutNewCall() {
        client.behavior = command -> new ApprovalWithdrawalResult(command.withdrawalRequestId(), command.documentId(), ApprovalWithdrawalResult.Outcome.UNKNOWN);
        cancel();
        ApprovalWithdrawalCommand initial = client.commands.getFirst();
        client.lookup = id -> confirmed(initial);
        assertThat(cancel().adminCancelStatus()).isEqualTo(AdminCancellationStatus.COMPLETED);
        assertThat(client.calls).hasValue(1);
        assertThat(releaseCount()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "CONSUME", "DOCUMENT"})
    void remoteSuccessStillRevalidatesCurrentStateAndLedger(String change) {
        client.behavior = command -> {
            if (change.equals("APPROVED")) {
                jdbc.update("update leave_request set status='APPROVED' where leave_req_id=100");
            } else if (change.equals("DOCUMENT")) {
                jdbc.update("update leave_request set approval_doc_id=9999 where leave_req_id=100");
            } else {
                jdbc.update("""
                        insert into leave_ledger(account_id,grant_id,leave_req_id,allocation_id,event_type,quantity_delta,reserved_delta,idempotency_key)
                        values(901,1001,100,1,'CONSUME',-240,-240,'late-consume')
                        """);
            }
            return confirmed(command);
        };
        assertThatThrownBy(this::cancel).isInstanceOf(BusinessException.class);
        assertThat(releaseCount()).isZero();
        assertThat(request().get("cancelled_at")).isNull();
        assertThat(request().get("admin_cancel_status")).isEqualTo("IN_PROGRESS");
        assertThat(client.calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_RESERVE", "DUPLICATE_RESERVE", "WRONG_RESERVE", "RELEASE", "CONSUME", "DATE_TOTAL", "HEADER_TOTAL", "LOW_RESERVED", "NEGATIVE_QUANTITY", "REVERSED"})
    void rejectsCorruptReservationBeforeRemoteCall(String change) {
        switch (change) {
            case "MISSING_RESERVE" -> jdbc.update("delete from leave_ledger where entry_id=21");
            case "DUPLICATE_RESERVE" -> jdbc.update("""
                    insert into leave_ledger(account_id,grant_id,leave_req_id,allocation_id,event_type,reserved_delta,idempotency_key)
                    values(901,1001,100,1,'RESERVE',240,'duplicate')
                    """);
            case "WRONG_RESERVE" -> jdbc.update("update leave_ledger set reserved_delta=239 where entry_id=21");
            case "RELEASE", "CONSUME" -> jdbc.update("""
                    insert into leave_ledger(account_id,grant_id,leave_req_id,allocation_id,event_type,reserved_delta,idempotency_key)
                    values(901,1001,100,1,?,-240,'already-processed')
                    """, change);
            case "DATE_TOTAL" -> jdbc.update("update leave_request_date set deduct_minutes=479 where request_date_id=101");
            case "HEADER_TOTAL" -> jdbc.update("update leave_request set total_deduct_minutes=959 where leave_req_id=100");
            case "LOW_RESERVED" -> jdbc.update("""
                    insert into leave_ledger(account_id,grant_id,event_type,reserved_delta,idempotency_key)
                    values(901,1001,'RELEASE',-240,'unlinked-release')
                    """);
            case "NEGATIVE_QUANTITY" -> jdbc.update("update leave_ledger set quantity_delta=-1 where entry_id=11");
            case "REVERSED" -> jdbc.update("""
                    insert into leave_ledger(account_id,grant_id,event_type,reserved_delta,reverses_entry_id,idempotency_key)
                    values(901,1001,'RELEASE',-1,21,'reverse-original')
                    """);
            default -> fail("unknown scenario");
        }
        assertBusiness(() -> cancel(), ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(client.calls).hasValue(0);
        assertThat(request().get("admin_cancel_status")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "future"})
    void releasesOriginalExpiredOrFutureGrantsWithoutReallocating(String validity) {
        if (validity.equals("expired")) {
            jdbc.update("update leave_grant set expires_on='2001-01-01Z' where grant_id in (1001,1002)");
        } else {
            jdbc.update("update leave_grant set valid_from='2900-01-01Z' where grant_id in (1001,1002)");
        }
        cancel();
        assertThat(reserved()).isZero();
        assertThat(releaseCount()).isEqualTo(3);
    }

    @Test
    void releaseInsertFailureRollsBackAllFinalWritesButKeepsCommittedIntent() {
        jdbc.execute("""
                create or replace function cancel_release_fail() returns trigger language plpgsql as $$
                begin
                    if new.event_type='RELEASE' and new.allocation_id=2 then raise exception 'forced release failure'; end if;
                    return new;
                end $$
                """);
        jdbc.execute("create trigger cancel_release_fail before insert on leave_ledger for each row execute function cancel_release_fail()");
        try {
            assertThatThrownBy(this::cancel).isInstanceOf(RuntimeException.class);
            assertThat(releaseCount()).isZero();
            assertThat(reserved()).isEqualTo(960);
            assertThat(request().get("status")).isEqualTo("SUBMITTED");
            assertThat(request().get("cancelled_by")).isNull();
            assertThat(request().get("admin_cancel_status")).isEqualTo("IN_PROGRESS");
        } finally {
            jdbc.execute("drop trigger cancel_release_fail on leave_ledger");
        }
        client.lookup = id -> confirmed(client.commands.getFirst());
        assertThat(cancel().adminCancelStatus()).isEqualTo(AdminCancellationStatus.COMPLETED);
        assertThat(client.calls).hasValue(1);
        assertThat(client.lookups).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {10, 11})
    void simultaneousCancellationNeverCallsWithdrawTwiceAndFirstActorWins(long secondAdmin) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        client.behavior = command -> {
            entered.countDown();
            await(finish);
            return confirmed(command);
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AdminLeaveCancellationResponse> first = executor.submit(() -> {
                TestTokens.authenticate(10, "ADMIN");
                try {
                    return cancel();
                } finally {
                    TestTokens.clear();
                }
            });
            try {
                assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                TestTokens.authenticate(secondAdmin, "ADMIN");
                if (secondAdmin == 11) {
                    assertBusiness(() -> cancel(), ErrorCode.LEAVE_CANCELLATION_CONFLICT);
                } else {
                    assertThat(cancel().adminCancelStatus()).isEqualTo(AdminCancellationStatus.UNKNOWN);
                }
                assertThat(reserved()).isEqualTo(960);
            } finally {
                finish.countDown();
            }
            assertThat(first.get(20, TimeUnit.SECONDS).cancelledBy()).isEqualTo(10);
            assertThat(client.calls).hasValue(1);
            assertThat(releaseCount()).isEqualTo(3);
        }
    }

    @Test
    void pendingCancellationDoesNotHoldLockDuringIoAndAdjustUsesLatestReservation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        client.behavior = command -> {
            entered.countDown();
            await(finish);
            return confirmed(command);
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AdminLeaveCancellationResponse> cancellation = executor.submit(() -> {
                TestTokens.authenticate(10, "ADMIN");
                try {
                    return cancel();
                } finally {
                    TestTokens.clear();
                }
            });
            try {
                assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                TestTokens.authenticate(11, "ADMIN");
                adjustments.adjust(42L, "during-withdrawal", new LeaveAdjustmentRequest(901L, 1001L, -240, "가용량 정정"));
            } finally {
                finish.countDown();
            }
            assertThat(cancellation.get(20, TimeUnit.SECONDS).cancelledBy()).isEqualTo(10);
            assertThat(reserved()).isZero();
            assertThat(jdbc.queryForObject("select sum(quantity_delta) from leave_ledger where grant_id=1001", Long.class)).isEqualTo(240);
        }
    }

    @Test
    void employeeLockWaitReadsNewStatusAndDoesNotBlockOtherEmployees() throws Exception {
        try (Connection writer = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            writer.createStatement().execute("select pg_advisory_xact_lock(-42::bigint)");
            Future<?> waiting = executor.submit(() -> {
                TestTokens.authenticate(10, "ADMIN");
                try {
                    assertBusiness(() -> cancel(), ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED);
                } finally {
                    TestTokens.clear();
                }
            });
            awaitLockWait();
            assertThat(service.cancel(200L, input(REASON)).status().name()).isEqualTo("CANCELLED");
            writer.createStatement().executeUpdate("update leave_request set status='APPROVED' where leave_req_id=100");
            writer.commit();
            waiting.get(20, TimeUnit.SECONDS);
            assertThat(request().get("admin_cancel_status")).isNull();
            assertThat(releaseCount()).isZero();
        }
    }

    @Test
    void originalT2CannotReleaseAgainOrOverwriteAdminCancellation() {
        cancel();
        TestTokens.authenticate(42, "USER");
        UUID originalTrace = UUID.fromString("00000000-0000-0000-0000-000000000100");
        assertBusiness(() -> approvalResults.confirmNotCreated(100L, originalTrace, "late failure"), ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(approvalResults.linkCreatedDocument(100L, originalTrace, 9001L).status().name()).isEqualTo("CANCELLED");
        assertThat(releaseCount()).isEqualTo(3);
        assertThat(request().get("cancelled_by")).isEqualTo(10L);
    }

    @Test
    void orchestrationRejectsOuterTransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> cancel()))
                .isInstanceOf(com.ssafy.leaveservice.global.exception.BusinessException.class);
        assertThat(client.calls).hasValue(0);
    }

    @Test
    void migrationPreservesV5RowsAndAddsOnlyFiveNullableColumns() {
        String schema = "v6_" + UUID.randomUUID().toString().replace("-", "");
        Flyway old = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("5").load();
        old.migrate();
        JdbcTemplate legacy = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema, postgres.getUsername(), postgres.getPassword()));
        legacy.update("insert into leave_policy_group(policy_id,name) values(1,'정책')");
        legacy.update("insert into leave_policy_version(policy_version_id,policy_id,version_no,base_type) values(1,1,1,'HIRE_DATE')");
        legacy.update("insert into leave_type(leave_type_id,name,balance_mode) values(1,'비차감','NONE')");
        legacy.update("insert into leave_request(leave_req_id,emp_id,policy_version_id,leave_type_id,status) values(1,42,1,1,'DRAFT'),(2,42,1,1,'APPROVED')");
        List<Map<String, Object>> before = legacy.queryForList("select to_jsonb(r)::text as row,xmin::text as version from leave_request r order by leave_req_id");
        Flyway latest = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load();
        latest.migrate();
        latest.validate();
        assertThat(legacy.queryForList("""
                select (to_jsonb(r)-array['admin_cancel_status','admin_cancel_request_id','admin_cancel_requested_by',
                    'admin_cancel_reason','admin_cancel_status_changed_at'])::text as row,xmin::text as version
                from leave_request r order by leave_req_id
                """)).isEqualTo(before);
        assertThat(legacy.queryForObject("select count(*) from information_schema.columns where table_schema=? and table_name='leave_request' and column_name like 'admin_cancel_%' and is_nullable='YES'", Integer.class, schema)).isEqualTo(5);
        assertThatThrownBy(() -> legacy.update("update leave_request set admin_cancel_status='UNKNOWN' where leave_req_id=1")).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARTIAL", "NULL_TRACE", "BLANK_REASON", "ZERO_ACTOR", "INVALID_STATE", "COMPLETED_WITHOUT_AUDIT"})
    void v6RejectsIncompleteOrInconsistentCancellationMetadata(String invalid) {
        if (invalid.equals("PARTIAL")) {
            assertThatThrownBy(() -> jdbc.update("update leave_request set admin_cancel_request_id=? where leave_req_id=100", UUID.randomUUID()))
                    .isInstanceOf(DataAccessException.class);
            return;
        }
        assertThatThrownBy(() -> jdbc.update("""
                update leave_request set admin_cancel_status=?,admin_cancel_request_id=?,admin_cancel_requested_by=?,
                    admin_cancel_reason=?,admin_cancel_status_changed_at=clock_timestamp() where leave_req_id=100
                """,
                invalid.equals("INVALID_STATE") ? "INVALID" : invalid.equals("COMPLETED_WITHOUT_AUDIT") ? "COMPLETED" : "IN_PROGRESS",
                invalid.equals("NULL_TRACE") ? null : UUID.randomUUID(),
                invalid.equals("ZERO_ACTOR") ? 0L : 10L,
                invalid.equals("BLANK_REASON") ? " " : REASON)).isInstanceOf(DataAccessException.class);
        assertThat(request().get("admin_cancel_status")).isNull();
    }

    @Test
    void v6CancellationUuidIsUniqueAcrossOriginalRequests() {
        UUID operationId = transactions.start(100L, REASON).command().withdrawalRequestId();
        assertThatThrownBy(() -> jdbc.update("""
                update leave_request set admin_cancel_status='IN_PROGRESS',admin_cancel_request_id=?,
                    admin_cancel_requested_by=10,admin_cancel_reason=?,admin_cancel_status_changed_at=clock_timestamp()
                where leave_req_id=200
                """, operationId, REASON)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select admin_cancel_status from leave_request where leave_req_id=200", String.class)).isNull();
    }

    private AdminLeaveCancellationResponse cancel() {
        return service.cancel(100L, input(REASON));
    }

    private AdminLeaveCancellationRequest input(String reason) {
        return new AdminLeaveCancellationRequest(reason);
    }

    private static ApprovalWithdrawalResult confirmed(ApprovalWithdrawalCommand command) {
        return new ApprovalWithdrawalResult(command.withdrawalRequestId(), command.documentId(), ApprovalWithdrawalResult.Outcome.WITHDRAWN_CONFIRMED);
    }

    private static String body(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private Map<String, Object> request() {
        return jdbc.queryForMap("select *,xmin::text as row_version from leave_request where leave_req_id=100");
    }

    private long reserved() {
        return jdbc.queryForObject("select sum(reserved_delta) from leave_ledger where leave_req_id=100", Long.class);
    }

    private int releaseCount() {
        return jdbc.queryForObject("select count(*) from leave_ledger where event_type='RELEASE'", Integer.class);
    }

    private List<Map<String, Object>> rows(String table) {
        return jdbc.queryForList("select *,xmin::text as row_version from " + table + " order by 1");
    }

    private static RequestPostProcessor admin(long id) {
        return jwt().jwt(j -> j.subject(String.valueOf(id)).claim("role", "ADMIN").claim("type", "access").claim("dutyId",id))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static void assertBusiness(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("외부 호출 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void awaitLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and pid<>pg_backend_pid()", Integer.class) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("대상 직원 잠금 대기가 확인되어야 합니다.");
    }
}
