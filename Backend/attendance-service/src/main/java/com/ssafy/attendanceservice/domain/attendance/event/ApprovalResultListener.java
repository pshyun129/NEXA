package com.ssafy.attendanceservice.domain.attendance.event;

import com.ssafy.attendanceservice.domain.attendance.client.LeaveClient;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.service.AttendanceRequestService;
import com.ssafy.attendanceservice.domain.attendance.service.LeaveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결재 결과 이벤트 수신. 근태 문서는 신청서에, 휴가 문서는 근태 휴가에 반영한다.
 * 처리 중 예외는 잡지 않는다: 재시도 후 DLT (KafkaConfig). 신청서가 아직 커밋 전이거나 휴가 서비스가 잠깐 응답하지 않으면 재시도에서 처리된다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalResultListener {

    static final String TOPIC = "approval.document.result";
    private static final String EVENT_COMPLETED = "DOCUMENT_COMPLETED";
    private static final String SOURCE_ATTENDANCE = "ATTENDANCE";
    private static final String SOURCE_LEAVE = "LEAVE";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_CANCEL = "CANCEL";

    private final JsonMapper jsonMapper;
    private final AttendanceRequestService attendanceRequestService;
    private final LeaveClient leaveClient;
    private final LeaveService leaveService;

    @KafkaListener(topics = TOPIC)
    public void onApprovalResult(String payload) {
        ApprovalResultEvent event = jsonMapper.readValue(payload, ApprovalResultEvent.class);

        // 다른 종류의 이벤트, 다른 서비스(업무 자동화 등)의 문서는 무시
        if (!EVENT_COMPLETED.equals(event.eventType())) {
            return;
        }
        if (SOURCE_ATTENDANCE.equals(event.sourceType())) {
            applyAttendanceRequest(event);
        } else if (SOURCE_LEAVE.equals(event.sourceType())) {
            applyLeave(event);
        }
    }

    /** 근태 신청서에 결재 결과 반영 */
    private void applyAttendanceRequest(ApprovalResultEvent event) {
        AttendanceRequestStatus result = toRequestStatus(event.status());
        if (result == null) {
            log.warn("모르는 결재 결과라 무시: eventId={}, status={}", event.eventId(), event.status());
            return;
        }

        log.info("결재 결과 반영: eventId={}, documentId={}, attReqId={}, status={}",
                event.eventId(), event.documentId(), event.sourceId(), event.status());
        attendanceRequestService.applyApprovalResult(event.sourceId(), result);
    }

    /**
     * 휴가 결재 결과 반영. 승인이면 휴가 서비스에서 날짜를 받아 저장하고, 회수면 저장한 휴가를 지운다.
     * 승인 여부는 이 이벤트로 판단한다 (휴가 서비스가 아직 승인을 반영하기 전이어도 날짜는 상신 때 정해져 있음).
     * 반려는 저장한 휴가가 없으므로 할 일이 없다
     */
    private void applyLeave(ApprovalResultEvent event) {
        Long leaveReqId = event.sourceId();
        if (STATUS_APPROVED.equals(event.status())) {
            log.info("휴가 승인 반영: eventId={}, documentId={}, leaveReqId={}",
                    event.eventId(), event.documentId(), leaveReqId);
            leaveClient.getRequestDates(leaveReqId).ifPresentOrElse(
                    dates -> leaveService.applyApprovedLeave(dates.toApprovedLeave()),
                    () -> {
                        // 그 사이 취소된 신청 (늦게 처리된 승인 이벤트): 저장하지 않고, 혹시 남은 휴가도 지운다
                        log.info("휴가 승인 반영 안 함 (휴가 서비스에서 취소됐거나 없는 신청): leaveReqId={}", leaveReqId);
                        leaveService.cancelLeave(leaveReqId);
                    });
        } else if (STATUS_CANCEL.equals(event.status())) {
            log.info("휴가 회수 반영: eventId={}, documentId={}, leaveReqId={}",
                    event.eventId(), event.documentId(), leaveReqId);
            leaveService.cancelLeave(leaveReqId);
        }
    }

    /** 결재 문서 상태 → 근태 신청 상태 */
    private static AttendanceRequestStatus toRequestStatus(String status) {
        return switch (status == null ? "" : status) {
            case "APPROVED" -> AttendanceRequestStatus.APPROVED;
            case "REJECTED" -> AttendanceRequestStatus.REJECTED;
            case "CANCEL" -> AttendanceRequestStatus.CANCELED;
            default -> null;
        };
    }
}
