package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalClient;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalCommand;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalProperties;
import com.ssafy.leaveservice.client.approval.withdrawal.ApprovalWithdrawalResult;
import com.ssafy.leaveservice.domain.request.dto.AdminCancellationAttempt;
import com.ssafy.leaveservice.domain.request.dto.AdminLeaveCancellationRequest;
import com.ssafy.leaveservice.domain.request.dto.AdminLeaveCancellationResponse;
import com.ssafy.leaveservice.domain.request.entity.AdminCancellationStatus;
import com.ssafy.leaveservice.domain.request.entity.ApprovalCreationStatus;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.service.AdminLeaveCancellationService;
import com.ssafy.leaveservice.domain.request.service.AdminLeaveCancellationTransactionService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminLeaveCancellationServiceTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final EmployeeAccess access = mock(EmployeeAccess.class);
    private final ApprovalWithdrawalClient client = mock(ApprovalWithdrawalClient.class);
    private final AdminLeaveCancellationTransactionService transactions = mock(AdminLeaveCancellationTransactionService.class);
    private final UUID operation = UUID.randomUUID();
    private final ApprovalWithdrawalCommand command = new ApprovalWithdrawalCommand(100L, 42L, 9001L, operation, 10L, "취소");
    private AdminLeaveCancellationService service;

    @BeforeEach
    void setUp() {
        service = new AdminLeaveCancellationService(access, factory.getValidator(), new ApprovalWithdrawalProperties(true), client, transactions);
        when(access.currentEmployeeId()).thenReturn(10L);
        when(access.isAdmin(10L)).thenReturn(true);
        when(client.isAvailable()).thenReturn(true);
        when(transactions.start(100L, "취소")).thenReturn(new AdminCancellationAttempt(response(AdminCancellationStatus.IN_PROGRESS), command, true));
        when(transactions.complete(command)).thenReturn(response(AdminCancellationStatus.COMPLETED));
        when(transactions.recordUnknown(command)).thenReturn(response(AdminCancellationStatus.UNKNOWN));
        when(transactions.confirmNotWithdrawn(command)).thenReturn(response(AdminCancellationStatus.FAILED));
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @Test
    void disabledBlocksBeforeAnyLocalTransactionOrClientCall() {
        service = new AdminLeaveCancellationService(access, factory.getValidator(), new ApprovalWithdrawalProperties(false), client, transactions);
        assertBusiness(ErrorCode.APPROVAL_WITHDRAWAL_UNAVAILABLE);
        verifyNoInteractions(transactions, client);
    }

    @Test
    void unavailableClientAlsoBlocksBeforeLocalTransaction() {
        when(client.isAvailable()).thenReturn(false);
        assertBusiness(ErrorCode.APPROVAL_WITHDRAWAL_UNAVAILABLE);
        verifyNoInteractions(transactions);
    }

    @Test
    void checksAdminBeforeAnyLocalOrRemoteOperation() {
        when(access.isAdmin(10L)).thenReturn(false);
        assertBusiness(ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(transactions, client);
    }

    @Test
    void onlyConfirmedMatchingDocumentCompletes() {
        when(client.withdraw(command)).thenReturn(new ApprovalWithdrawalResult(operation, 9001L, ApprovalWithdrawalResult.Outcome.WITHDRAWN_CONFIRMED));
        assertThat(cancel().adminCancelStatus()).isEqualTo(AdminCancellationStatus.COMPLETED);
        verify(transactions).complete(command);
        verify(client, never()).findByRequestId(any());
        verify(transactions, never()).recordUnknown(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "NULL_OUTCOME", "WRONG_DOCUMENT", "WRONG_UUID", "ACCEPTED", "UNKNOWN", "EXCEPTION"})
    void uncertainResultsKeepReservationAndNeverConfirmFailure(String scenario) {
        ApprovalWithdrawalResult result = switch (scenario) {
            case "NULL" -> null;
            case "NULL_OUTCOME" -> new ApprovalWithdrawalResult(operation, 9001L, null);
            case "WRONG_DOCUMENT" -> new ApprovalWithdrawalResult(operation, 9002L, ApprovalWithdrawalResult.Outcome.WITHDRAWN_CONFIRMED);
            case "WRONG_UUID" -> new ApprovalWithdrawalResult(UUID.randomUUID(), 9001L, ApprovalWithdrawalResult.Outcome.WITHDRAWN_CONFIRMED);
            default -> new ApprovalWithdrawalResult(operation, 9001L, ApprovalWithdrawalResult.Outcome.UNKNOWN);
        };
        if (scenario.equals("EXCEPTION")) {
            when(client.withdraw(command)).thenThrow(new IllegalStateException("timeout"));
        } else if (scenario.equals("ACCEPTED")) {
            when(client.withdraw(command)).thenReturn(new ApprovalWithdrawalResult(operation, 9001L, ApprovalWithdrawalResult.Outcome.ACCEPTED));
        } else {
            when(client.withdraw(command)).thenReturn(result);
        }
        assertThat(cancel().adminCancelStatus()).isEqualTo(AdminCancellationStatus.UNKNOWN);
        verify(transactions).recordUnknown(command);
        verify(transactions, never()).complete(any());
        verify(transactions, never()).confirmNotWithdrawn(any());
    }

    @Test
    void pendingReplayOnlyQueriesExistingUuid() {
        when(transactions.start(100L, "취소")).thenReturn(new AdminCancellationAttempt(response(AdminCancellationStatus.UNKNOWN), command, false));
        when(client.findByRequestId(operation)).thenReturn(new ApprovalWithdrawalResult(operation, 9001L, ApprovalWithdrawalResult.Outcome.WITHDRAWN_CONFIRMED));
        assertThat(cancel().adminCancelStatus()).isEqualTo(AdminCancellationStatus.COMPLETED);
        verify(client, never()).withdraw(any());
        verify(client).findByRequestId(operation);
    }

    @Test
    void completedReplayNeverCallsApprovalAgain() {
        when(transactions.start(100L, "취소")).thenReturn(new AdminCancellationAttempt(response(AdminCancellationStatus.COMPLETED), command, false));
        assertThat(cancel().adminCancelStatus()).isEqualTo(AdminCancellationStatus.COMPLETED);
        verify(client, never()).withdraw(any());
        verify(client, never()).findByRequestId(any());
    }

    @Test
    void confirmedFailureIsPersistedBeforeReturningConflict() {
        when(client.withdraw(command)).thenReturn(new ApprovalWithdrawalResult(operation, 9001L, ApprovalWithdrawalResult.Outcome.NOT_WITHDRAWN_CONFIRMED));
        assertBusiness(ErrorCode.LEAVE_CANCELLATION_NOT_ALLOWED);
        verify(transactions).confirmNotWithdrawn(command);
        verify(transactions, never()).complete(any());
    }

    private AdminLeaveCancellationResponse cancel() {
        return service.cancel(100L, new AdminLeaveCancellationRequest("취소"));
    }

    private void assertBusiness(ErrorCode expected) {
        assertThatThrownBy(this::cancel).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    private AdminLeaveCancellationResponse response(AdminCancellationStatus status) {
        return new AdminLeaveCancellationResponse(100L,
                status == AdminCancellationStatus.COMPLETED ? LeaveRequestStatus.CANCELLED : LeaveRequestStatus.SUBMITTED,
                ApprovalCreationStatus.LINKED, 9001L, status, operation, null, null, null, "상태 안내");
    }
}
