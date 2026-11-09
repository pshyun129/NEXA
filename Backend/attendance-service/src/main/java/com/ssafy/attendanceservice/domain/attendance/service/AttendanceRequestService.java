package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestDetailResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestPreviewRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestPreviewResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestSubmitRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestSummaryResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;

import java.util.List;

public interface AttendanceRequestService {

    /** 신청 검증 및 결과 미리보기 (저장하지 않음) */
    AttendanceRequestPreviewResponse preview(Long empId, AttendanceRequestPreviewRequest request);

    /**
     * 신청서 상신. 미리보기와 같은 검증 후 저장하고 결재 문서를 만든다.
     * 결재 문서 생성에 실패하면 신청서도 저장하지 않는다 (BusinessException(APPROVAL_*)).
     */
    AttendanceRequestDetailResponse submit(Long empId, AttendanceRequestSubmitRequest request);

    /** 내 신청 이력 (해당 연도에 상신한 신청, 최신순). requestType이 null이면 전체 유형 */
    List<AttendanceRequestSummaryResponse> getMyRequests(Long empId, int year, AttendanceRequestType requestType);

    /** 내 신청서 상세. 없거나 본인 신청서가 아니면 BusinessException(ATTENDANCE_REQUEST_NOT_FOUND) */
    AttendanceRequestDetailResponse getMyRequest(Long empId, Long requestId);

    /**
     * 신청서 회수. 결재 대기 중인 본인 신청서만 회수하고, 결재 문서도 함께 회수한다.
     * 없거나 본인 신청서가 아니면 BusinessException(ATTENDANCE_REQUEST_NOT_FOUND),
     * 결재 대기가 아니면 BusinessException(REQUEST_NOT_PENDING),
     * 결재가 이미 진행됐으면 BusinessException(APPROVAL_CANCEL_REJECTED)
     */
    AttendanceRequestDetailResponse cancel(Long empId, Long requestId);

    /**
     * 결재 결과 반영 (결재 결과 이벤트 수신 시 호출).
     * 결재 대기(PENDING)일 때만 반영하고 이미 처리된 신청은 무시한다 (같은 결과가 두 번 와도 결과가 같다).
     * 승인이면 신청 내용을 근태에 적용한다.
     * 신청서가 없으면 BusinessException(ATTENDANCE_REQUEST_NOT_FOUND): 상신 커밋 전에 결과가 먼저 온 경우라 재시도 대상
     *
     * @param result APPROVED, REJECTED, CANCELED 중 하나
     */
    void applyApprovalResult(Long attReqId, AttendanceRequestStatus result);
}
