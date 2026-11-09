package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.client.approval.*;
import com.ssafy.leaveservice.domain.request.dto.*;
import com.ssafy.leaveservice.domain.request.entity.*;
import com.ssafy.leaveservice.domain.request.service.*;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveSubmissionOrchestrationServiceTest {
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final ApprovalClient client = mock(ApprovalClient.class);
    private final LeaveSubmissionService t1 = mock(LeaveSubmissionService.class);
    private final LeaveApprovalResultService t2 = mock(LeaveApprovalResultService.class);
    private final MyLeaveRequestService queries = mock(MyLeaveRequestService.class);
    private final UUID traceId = UUID.randomUUID();
    private final LeaveRequestPreviewRequest input = new LeaveRequestPreviewRequest(1L,"reason",
            List.of(new LeaveRequestPreviewRequest.DateRequest(LocalDate.of(2030,1,7),LeaveRequestPortion.FULL)));
    private final LeaveSubmissionResult pending = new LeaveSubmissionResult(7L,1L,traceId,LeaveRequestStatus.SUBMITTING,ApprovalCreationStatus.NOT_SENT,null);
    private final LeaveSubmissionResult unknown = new LeaveSubmissionResult(7L,1L,traceId,LeaveRequestStatus.SUBMITTING,ApprovalCreationStatus.UNKNOWN,null);
    private LeaveSubmissionOrchestrationService service;

    @BeforeEach
    void setUp() {
        service = service(true);
        when(employeeAccess.currentEmployeeId()).thenReturn(1L);
        when(client.isAvailable()).thenReturn(true);
        when(t1.submit("key",input)).thenReturn(pending);
        when(t2.startCall(7L,traceId)).thenReturn(new LeaveApprovalCallStartResult(pending,true));
        OffsetDateTime now = OffsetDateTime.parse("2030-01-01T00:00:00Z");
        MyLeaveRequestDetailResponse detail = new MyLeaveRequestDetailResponse(7L,1L,11L,1L,"연차",null,
                LeaveRequestStatus.SUBMITTING,480,480,"reason",null,null,null,null,now,now,
                List.of(new LeaveRequestDateResponse(8L,now,LeaveRequestPortion.FULL,null,null,480,480,480)));
        when(queries.getMyRequest(7L)).thenReturn(detail);
        when(t2.recordUnknown(eq(7L),eq(traceId),anyString())).thenReturn(unknown);
    }

    @ParameterizedTest
    @EnumSource(value = ApprovalCreationStatus.class, names = {"IN_PROGRESS", "UNKNOWN"})
    void replaysPendingSubmissionByLookupWithoutAnotherCreate(ApprovalCreationStatus creationStatus) {
        LeaveSubmissionResult existing = new LeaveSubmissionResult(
                7L, 1L, traceId, LeaveRequestStatus.SUBMITTING, creationStatus, null
        );
        LeaveSubmissionResult linked = new LeaveSubmissionResult(
                7L, 1L, traceId, LeaveRequestStatus.SUBMITTED, ApprovalCreationStatus.LINKED, 901L
        );
        when(t2.startCall(7L, traceId)).thenReturn(new LeaveApprovalCallStartResult(existing, false));
        when(client.supportsRecovery()).thenReturn(true);
        when(client.recover(existing)).thenReturn(
                new ApprovalCreationResult(traceId, ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED, 901L)
        );
        when(t2.linkCreatedDocument(7L, traceId, 901L)).thenReturn(linked);

        assertThat(service.submit("key", input)).isEqualTo(linked);
        verify(client, never()).create(any());
        verifyNoInteractions(queries);
    }

    @Test
    void disabledGatePreventsT1AndAnyExternalClientAction() {
        assertCode(()->service(false).submit("key",input),ErrorCode.APPROVAL_INTEGRATION_UNAVAILABLE);
        verifyNoInteractions(t1,t2,queries,client);
    }

    @Test
    void enabledButUnavailableClientStillPreventsT1() {
        when(client.isAvailable()).thenReturn(false);
        assertCode(()->service.submit("key",input),ErrorCode.APPROVAL_INTEGRATION_UNAVAILABLE);
        verifyNoInteractions(t1,t2,queries);
        verify(client,never()).create(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs={0,-1})
    void invalidAuthenticationStopsBeforeGateAndT1(Long employeeId) {
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        assertCode(()->service.submit("key",input),ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(t1,t2,queries,client);
    }

    @Test
    void productionAuthentication503IsPreserved() {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_UNAVAILABLE));
        assertCode(()->service.submit("key",input),ErrorCode.AUTHENTICATION_UNAVAILABLE);
        verifyNoInteractions(t1,t2,queries,client);
    }

    @ParameterizedTest
    @ValueSource(strings={"SUBMITTED","CANCELLED","APPROVED","REJECTED","WITHDRAWN"})
    void existingFinalRequestsReturnDirectlyWithoutAnyT2OrCreate(String state) {
        LeaveSubmissionResult existing = new LeaveSubmissionResult(7L,1L,traceId,LeaveRequestStatus.valueOf(state),
                state.equals("CANCELLED") ? ApprovalCreationStatus.FAILED : ApprovalCreationStatus.LINKED,
                state.equals("CANCELLED") ? null : 901L);
        when(t1.submit("key",input)).thenReturn(existing);
        assertThat(service.submit("key",input)).isSameAs(existing);
        verifyNoInteractions(t2,queries);
        verify(client,never()).create(any());
    }

    @ParameterizedTest
    @ValueSource(strings={"IN_PROGRESS","UNKNOWN"})
    void callRequiredFalseNeverCreatesOrLooksUpAnotherDocument(String state) {
        LeaveSubmissionResult existing = new LeaveSubmissionResult(7L,1L,traceId,LeaveRequestStatus.SUBMITTING,
                ApprovalCreationStatus.valueOf(state),null);
        when(t2.startCall(7L,traceId)).thenReturn(new LeaveApprovalCallStartResult(existing,false));
        assertThat(service.submit("key",input)).isSameAs(existing);
        verifyNoInteractions(queries);
        verify(client,never()).create(any());
        verify(client,never()).findByRequestId(any());
    }

    @Test
    void t1FailurePropagatesAndNeverStartsApproval() {
        when(t1.submit("key",input)).thenThrow(new BusinessException(ErrorCode.LEAVE_REQUEST_NOT_ALLOWED));
        assertCode(()->service.submit("key",input),ErrorCode.LEAVE_REQUEST_NOT_ALLOWED);
        verifyNoInteractions(t2,queries);
        verify(client,never()).create(any());
    }

    @Test
    void successUsesPersistedSnapshotAndLinksConfirmedSubmissionOnly() {
        when(client.create(any())).thenReturn(new ApprovalCreationResult(traceId,ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,901L));
        service.submit("key",input);
        org.mockito.ArgumentCaptor<ApprovalCreateCommand> command = org.mockito.ArgumentCaptor.forClass(ApprovalCreateCommand.class);
        verify(client).create(command.capture());
        assertThat(command.getValue().approvalRequestId()).isEqualTo(traceId);
        assertThat(command.getValue().policyVersionId()).isEqualTo(11);
        assertThat(command.getValue().dates().getFirst().requestDateId()).isEqualTo(8);
        verify(t2).linkCreatedDocument(7L,traceId,901L);
        verify(t2,never()).confirmNotCreated(any(),any(),any());
    }

    @Test
    void explicitAuthoritativeAbsenceAloneAllowsCompensation() {
        when(client.create(any())).thenReturn(new ApprovalCreationResult(traceId,ApprovalCreationResult.Outcome.NOT_CREATED_CONFIRMED,null));
        service.submit("key",input);
        verify(t2).confirmNotCreated(7L,traceId,"APPROVAL_NOT_CREATED_CONFIRMED");
        verify(t2,never()).linkCreatedDocument(any(),any(),any());
    }

    @ParameterizedTest
    @ValueSource(strings={"NULL","TRACE","DRAFT","MISSING_DOC","CONTRADICTORY"})
    void unconfirmedOrMalformedOutcomeIsUnknown(String kind) {
        ApprovalCreationResult result = switch (kind) {
            case "NULL" -> null;
            case "TRACE" -> new ApprovalCreationResult(UUID.randomUUID(),ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,901L);
            case "DRAFT" -> new ApprovalCreationResult(traceId,ApprovalCreationResult.Outcome.DRAFT_CREATED,901L);
            case "MISSING_DOC" -> new ApprovalCreationResult(traceId,ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,null);
            default -> new ApprovalCreationResult(traceId,ApprovalCreationResult.Outcome.NOT_CREATED_CONFIRMED,901L);
        };
        when(client.create(any())).thenReturn(result);
        assertThat(service.submit("key",input)).isSameAs(unknown);
        verify(t2,never()).confirmNotCreated(any(),any(),any());
        verify(t2,never()).linkCreatedDocument(any(),any(),any());
    }

    @Test
    void unexpectedRuntimeIsUnknownButNeverImplicitlyConfirmedFailure() {
        when(client.create(any())).thenThrow(new IllegalStateException("private remote body"));
        assertThat(service.submit("key",input)).isSameAs(unknown);
        verify(t2).recordUnknown(7L,traceId,"APPROVAL_CLIENT_EXCEPTION");
        verify(t2,never()).confirmNotCreated(any(),any(),any());
    }

    @Test
    void t2LinkFailureIsNotCaughtAsRemoteFailureOrCompensated() {
        when(client.create(any())).thenReturn(new ApprovalCreationResult(traceId,ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED,901L));
        when(t2.linkCreatedDocument(7L,traceId,901L)).thenThrow(new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION));
        assertCode(()->service.submit("key",input),ErrorCode.DATA_INTEGRITY_VIOLATION);
        verify(t2,never()).recordUnknown(any(),any(),any());
        verify(t2,never()).confirmNotCreated(any(),any(),any());
    }

    private LeaveSubmissionOrchestrationService service(boolean enabled) {
        ApprovalProperties properties = new ApprovalProperties(enabled,"","",Duration.ofSeconds(2),Duration.ofSeconds(3));
        return new LeaveSubmissionOrchestrationService(employeeAccess,properties,client,t1,t2,queries);
    }

    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception->assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
