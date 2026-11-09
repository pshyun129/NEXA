package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.request.dto.AdminLeaveCancellationRequest;
import com.ssafy.leaveservice.domain.request.entity.AdminCancellationStatus;
import com.ssafy.leaveservice.domain.request.entity.ApprovalCreationStatus;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.global.exception.BusinessException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AdminLeaveCancellationRequestTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    void reasonMustNotBeNullOrBlank(String reason) {
        assertThat(factory.getValidator().validate(new AdminLeaveCancellationRequest(reason))).isNotEmpty();
    }

    @Test
    void reasonBoundaryIs2000WithoutTrimming() {
        String reason = " " + "가".repeat(1998) + " ";
        AdminLeaveCancellationRequest request = new AdminLeaveCancellationRequest(reason);
        assertThat(factory.getValidator().validate(request)).isEmpty();
        assertThat(request.reason()).isEqualTo(reason);
        assertThat(factory.getValidator().validate(new AdminLeaveCancellationRequest("가".repeat(2001)))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"reason\":1}", "{\"reason\":true}", "{\"reason\":{}}", "[]",
            "{\"reason\":\"취소\",\"cancelledBy\":999}", "{\"reason\":\"취소\",\"employeeId\":42}"})
    void onlyStringReasonCanBeProvidedByClient(String json) {
        assertThatThrownBy(() -> mapper.readValue(json, AdminLeaveCancellationRequest.class)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void operationFieldsNeverOverwriteAndFinalAuditIsOnlyWrittenOnCompletion() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-02T00:00:00Z");
        LeaveRequest request = LeaveRequest.submitting(42L, mock(LeaveType.class), 1L, 480, 480,
                "원래 사유", "submission-key", "a".repeat(64), now);
        UUID originalTrace = request.getApprovalRequestId();
        request.startApprovalCall(now);
        request.linkCreatedApproval(9001L, now);
        request.applyFinalApprovalDecision(LeaveRequestStatus.APPROVED, now);
        UUID cancellationTrace = UUID.randomUUID();
        request.startAdminCancellation(cancellationTrace, 10L, " 취소 사유 ", now);
        request.recordAdminCancellationUnknown(now.plusSeconds(1));
        assertThat(request.getCancelledAt()).isNull();
        assertThat(request.getCancelledBy()).isNull();
        assertThat(request.getCancelReason()).isNull();
        request.completeAdminCancellation(now.plusSeconds(2));
        assertThat(request.getStatus()).isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThat(request.getAdminCancelStatus()).isEqualTo(AdminCancellationStatus.COMPLETED);
        assertThat(request.getAdminCancelRequestId()).isEqualTo(cancellationTrace);
        assertThat(request.getAdminCancelRequestedBy()).isEqualTo(10);
        assertThat(request.getCancelReason()).isEqualTo(" 취소 사유 ");
        assertThat(request.getApprovalRequestId()).isEqualTo(originalTrace);
        assertThat(request.getApprovalCreationStatus()).isEqualTo(ApprovalCreationStatus.LINKED);
        assertThat(request.getApprovalDocId()).isEqualTo(9001);
        assertThatThrownBy(() -> request.recordAdminCancellationUnknown(now.plusSeconds(3))).isInstanceOf(BusinessException.class);
    }
}
