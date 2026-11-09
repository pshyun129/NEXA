package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.client.ApprovalClient;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestDetailResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequest;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceRequestRepository;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 신청서 회수: 결재 문서 회수 호출, 상태 변경, 회수할 수 없는 경우. 결재 서비스는 목으로 대신한다. 테스트마다 롤백 */
@SpringBootTest(properties = {"spring.config.location=classpath:/attendance-ci.yml"})
@Testcontainers
@Transactional
class RequestCancelIntegrationTest {

    private static final long EMP = 7L;
    private static final long OTHER_EMP = 8L;
    private static final long DOC_ID = 128L;
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 21);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired AttendanceRequestService attendanceRequestService;
    @Autowired AttendanceRequestRepository attendanceRequestRepository;

    @MockitoBean ApprovalClient approvalClient;

    /** 상신이 끝난 결재 대기 신청서 (결재 문서 연결됨) */
    private AttendanceRequest pendingRequest() {
        AttendanceRequest request = AttendanceRequest.builder()
                .empId(EMP).reqType(AttendanceRequestType.OVERTIME).targetDate(WEDNESDAY).reason("ot").build();
        request.linkApprovalDocument(DOC_ID, "OVERTIME-2026-0001");
        return attendanceRequestRepository.save(request);
    }

    @Test
    void 결재_대기_신청서는_결재_문서와_함께_회수() {
        AttendanceRequest request = pendingRequest();

        AttendanceRequestDetailResponse response = attendanceRequestService.cancel(EMP, request.getAttReqId());

        verify(approvalClient).cancelDocument(DOC_ID);
        assertThat(request.getStatus()).isEqualTo(AttendanceRequestStatus.CANCELED);
        assertThat(request.getCompletedAt()).isNotNull();
        assertThat(response.status()).isEqualTo(AttendanceRequestStatus.CANCELED);
    }

    @Test
    void 회수_뒤에_온_CANCEL_결과는_무시() {
        AttendanceRequest request = pendingRequest();
        attendanceRequestService.cancel(EMP, request.getAttReqId());

        attendanceRequestService.applyApprovalResult(request.getAttReqId(), AttendanceRequestStatus.CANCELED);

        assertThat(request.getStatus()).isEqualTo(AttendanceRequestStatus.CANCELED);
    }

    @Test
    void 다른_사람_신청서는_404() {
        AttendanceRequest request = pendingRequest();

        assertThatThrownBy(() -> attendanceRequestService.cancel(OTHER_EMP, request.getAttReqId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ATTENDANCE_REQUEST_NOT_FOUND);
        verify(approvalClient, never()).cancelDocument(anyLong());
    }

    @Test
    void 이미_승인된_신청서는_409() {
        AttendanceRequest request = pendingRequest();
        attendanceRequestService.applyApprovalResult(request.getAttReqId(), AttendanceRequestStatus.APPROVED);

        assertThatThrownBy(() -> attendanceRequestService.cancel(EMP, request.getAttReqId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_NOT_PENDING);
        verify(approvalClient, never()).cancelDocument(anyLong());
    }

    @Test
    void 결재가_진행돼_회수가_거절되면_신청서는_그대로() {
        AttendanceRequest request = pendingRequest();
        doThrow(new BusinessException(ErrorCode.APPROVAL_CANCEL_REJECTED))
                .when(approvalClient).cancelDocument(DOC_ID);

        assertThatThrownBy(() -> attendanceRequestService.cancel(EMP, request.getAttReqId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.APPROVAL_CANCEL_REJECTED);
        assertThat(request.getStatus()).isEqualTo(AttendanceRequestStatus.PENDING);
    }
}
