package com.ssafy.attendanceservice.domain.attendance.event;

import com.ssafy.attendanceservice.domain.attendance.client.LeaveClient;
import com.ssafy.attendanceservice.domain.attendance.client.LeaveRequestDates;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;
import com.ssafy.attendanceservice.domain.attendance.service.AttendanceRequestService;
import com.ssafy.attendanceservice.domain.attendance.service.LeaveService;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 결재 결과 이벤트 → 근태 신청·휴가 반영 호출 (Kafka·스프링 없이) */
class ApprovalResultListenerTest {

    private final AttendanceRequestService service = mock(AttendanceRequestService.class);
    private final LeaveClient leaveClient = mock(LeaveClient.class);
    private final LeaveService leaveService = mock(LeaveService.class);
    private final ApprovalResultListener listener =
            new ApprovalResultListener(JsonMapper.builder().build(), service, leaveClient, leaveService);

    private static String event(String sourceType, long sourceId, String status) {
        return """
                {"eventId":"6f1c2a9e-0b7d-4c3f-9a51-2e8d7c4b1f03","eventVersion":1,"eventType":"DOCUMENT_COMPLETED",
                 "occurredAt":"2026-11-03T13:20:01.123+09:00","documentId":128,"docNo":"OVERTIME-2026-0001",
                 "formType":"OVERTIME","sourceType":"%s","sourceId":%d,
                 "requestKey":"3f6b2c1e-8d4a-4b7f-9c2e-1a5d7e9f0b12","status":"%s"}""".formatted(sourceType, sourceId, status);
    }

    @Test
    void 근태_문서_승인은_신청서_승인으로_반영() {
        listener.onApprovalResult(event("ATTENDANCE", 42, "APPROVED"));

        verify(service).applyApprovalResult(42L, AttendanceRequestStatus.APPROVED);
    }

    @Test
    void 반려는_반려로() {
        listener.onApprovalResult(event("ATTENDANCE", 42, "REJECTED"));

        verify(service).applyApprovalResult(42L, AttendanceRequestStatus.REJECTED);
    }

    @Test
    void 결재_서비스의_CANCEL은_근태의_CANCELED로() {
        listener.onApprovalResult(event("ATTENDANCE", 42, "CANCEL"));

        verify(service).applyApprovalResult(42L, AttendanceRequestStatus.CANCELED);
    }

    @Test
    void 휴가_문서_승인은_휴가_서비스에서_날짜를_받아_저장() {
        LeaveRequestDates dates = new LeaveRequestDates(42L, 7L, "연차", true,
                List.of(new LeaveRequestDates.LeaveDate(LocalDate.of(2026, 11, 9), LeavePortion.FULL)));
        given(leaveClient.getRequestDates(42L)).willReturn(Optional.of(dates));

        listener.onApprovalResult(event("LEAVE", 42, "APPROVED"));

        verify(leaveService).applyApprovedLeave(dates.toApprovedLeave());
        verify(service, never()).applyApprovalResult(anyLong(), any());
    }

    @Test
    void 그_사이_취소된_휴가의_승인은_저장하지_않고_남은_휴가도_지움() {
        given(leaveClient.getRequestDates(42L)).willReturn(Optional.empty());

        listener.onApprovalResult(event("LEAVE", 42, "APPROVED"));

        verify(leaveService, never()).applyApprovedLeave(any());
        verify(leaveService).cancelLeave(42L);
    }

    @Test
    void 휴가_문서_회수는_저장한_휴가를_지움() {
        listener.onApprovalResult(event("LEAVE", 42, "CANCEL"));

        verify(leaveService).cancelLeave(42L);
        verifyNoInteractions(leaveClient);
    }

    @Test
    void 휴가_문서_반려는_할_일_없음() {
        listener.onApprovalResult(event("LEAVE", 42, "REJECTED"));

        verifyNoInteractions(leaveClient, leaveService, service);
    }

    @Test
    void 휴가_서비스_호출이_실패하면_예외를_그대로_던져_재시도되게() {
        given(leaveClient.getRequestDates(42L)).willThrow(new BusinessException(ErrorCode.LEAVE_CALL_FAILED));

        assertThatThrownBy(() -> listener.onApprovalResult(event("LEAVE", 42, "APPROVED")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(leaveService);
    }

    @Test
    void 다른_출처_문서는_무시() {
        listener.onApprovalResult(event("AUTOMATION", 42, "APPROVED"));

        verifyNoInteractions(leaveClient, leaveService, service);
    }

    @Test
    void 모르는_결과는_무시() {
        listener.onApprovalResult(event("ATTENDANCE", 42, "HOLD"));

        verify(service, never()).applyApprovalResult(anyLong(), any());
    }

    @Test
    void 신청서가_없으면_예외를_그대로_던져_재시도되게() {
        doThrow(new BusinessException(ErrorCode.ATTENDANCE_REQUEST_NOT_FOUND))
                .when(service).applyApprovalResult(42L, AttendanceRequestStatus.APPROVED);

        assertThatThrownBy(() -> listener.onApprovalResult(event("ATTENDANCE", 42, "APPROVED")))
                .isInstanceOf(BusinessException.class);
    }
}
