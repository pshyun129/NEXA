package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.ledger.repository.LeaveLedgerRepository;
import com.ssafy.leaveservice.domain.request.entity.*;
import com.ssafy.leaveservice.domain.request.repository.*;
import com.ssafy.leaveservice.domain.request.service.LeaveApprovalResultService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveApprovalResultServiceTest {
    private final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final LeaveSubmissionLockRepository locks = mock(LeaveSubmissionLockRepository.class);
    private final LeaveRequestRepository requests = mock(LeaveRequestRepository.class);
    private final LeaveRequestAllocationRepository allocations = mock(LeaveRequestAllocationRepository.class);
    private final LeaveLedgerRepository ledger = mock(LeaveLedgerRepository.class);
    private final LeaveAccountRepository accounts = mock(LeaveAccountRepository.class);
    private final LeaveApprovalRecoveryRepository recovery = mock(LeaveApprovalRecoveryRepository.class);
    private final LeaveApprovalResultService service = new LeaveApprovalResultService(
            employeeAccess, entityManager, locks, requests, allocations, ledger, accounts, recovery);
    private LeaveRequest request;
    private final OffsetDateTime now = OffsetDateTime.parse("2030-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        request = LeaveRequest.submitting(1L, mock(LeaveType.class), 11L, 480, 480,
                null, "key", "a".repeat(64), now.minusMinutes(1));
        ReflectionTestUtils.setField(request, "requestId", 7L);
        when(employeeAccess.currentEmployeeId()).thenReturn(1L);
        when(requests.findOwnedForUpdate(7L, 1L)).thenReturn(Optional.of(request));
        when(locks.currentTime()).thenReturn(now);
    }

    @Test
    void startsExactlyOnceAndRetainsTraceUuid() {
        UUID traceId = request.getApprovalRequestId();
        assertThat(service.startCall(7L, traceId).callRequired()).isTrue();
        assertThat(service.startCall(7L, traceId).callRequired()).isFalse();
        assertThat(request.getApprovalAttemptCount()).isEqualTo(1);
        assertThat(request.getApprovalRequestId()).isEqualTo(traceId);
        verifyNoInteractions(ledger, allocations, accounts, recovery);
    }

    @Test
    void successNeverAccessesReservationOrConsumesIt() {
        service.linkCreatedDocument(7L, request.getApprovalRequestId(), 901L);
        assertThat(request.getStatus()).isEqualTo(LeaveRequestStatus.SUBMITTED);
        assertThat(request.getSubmittedAt()).isEqualTo(now);
        assertThat(request.getApprovalStatusChangedAt()).isEqualTo(now);
        assertThat(request.getApprovalAttemptCount()).isZero();
        verifyNoInteractions(ledger, allocations, accounts, recovery);
    }

    @Test
    void timeoutRemainsPendingAndCannotCauseAnotherCallOrAnyLedgerWrite() {
        UUID traceId = request.getApprovalRequestId();
        service.recordUnknown(7L, traceId, "TIMEOUT");
        assertThat(request.getStatus()).isEqualTo(LeaveRequestStatus.SUBMITTING);
        assertThat(request.getApprovalCreationStatus()).isEqualTo(ApprovalCreationStatus.UNKNOWN);
        assertThat(request.getCancelledAt()).isNull();
        assertThat(request.getApprovalDocId()).isNull();
        assertThat(service.startCall(7L, traceId).callRequired()).isFalse();
        verifyNoInteractions(ledger, allocations, accounts, recovery);
    }

    @ParameterizedTest
    @ValueSource(longs={0,-1})
    void rejectsNonPositiveDocumentId(long documentId) {
        assertCode(()->service.linkCreatedDocument(7L, request.getApprovalRequestId(), documentId), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(requests, ledger, allocations);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={"   "})
    void rejectsMissingDiagnosticReason(String reason) {
        assertCode(()->service.recordUnknown(7L, request.getApprovalRequestId(), reason), ErrorCode.INVALID_INPUT);
        assertCode(()->service.confirmNotCreated(7L, request.getApprovalRequestId(), reason), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(requests, ledger, allocations);
    }

    @Test
    void rejectsOverlongDiagnosticInsteadOfTruncatingIt() {
        assertCode(()->service.recordUnknown(7L, request.getApprovalRequestId(), "x".repeat(501)), ErrorCode.INVALID_INPUT);
        verifyNoInteractions(requests, ledger);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs={0,-1})
    void rejectsMissingAuthentication(Long employeeId) {
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        assertCode(()->service.startCall(7L, request.getApprovalRequestId()), ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(locks, requests, ledger);
    }

    @Test
    void retainsProductionAuthenticationFailure() {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));
        assertCode(()->service.startCall(7L, request.getApprovalRequestId()), ErrorCode.AUTHENTICATION_UNAVAILABLE);
        verifyNoInteractions(locks, requests, ledger);
    }

    @Test
    void refusesDifferentTraceUuidBeforeAnyTransition() {
        assertCode(()->service.linkCreatedDocument(7L, UUID.randomUUID(), 901L), ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(request.getStatus()).isEqualTo(LeaveRequestStatus.SUBMITTING);
        assertThat(request.getApprovalDocId()).isNull();
        verifyNoInteractions(ledger, allocations, accounts, recovery);
    }

    @Test
    void attemptCountOverflowDoesNotPartiallyMutateEntity() {
        ReflectionTestUtils.setField(request, "approvalAttemptCount", Integer.MAX_VALUE);
        assertCode(()->service.startCall(7L, request.getApprovalRequestId()), ErrorCode.DATA_INTEGRITY_VIOLATION);
        assertThat(request.getApprovalCreationStatus()).isEqualTo(ApprovalCreationStatus.NOT_SENT);
        assertThat(request.getApprovalAttemptCount()).isEqualTo(Integer.MAX_VALUE);
        assertThat(request.getUpdatedAt()).isEqualTo(now.minusMinutes(1));
    }

    private void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
