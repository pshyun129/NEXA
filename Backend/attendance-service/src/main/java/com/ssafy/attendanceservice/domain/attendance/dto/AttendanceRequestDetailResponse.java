package com.ssafy.attendanceservice.domain.attendance.dto;

import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequest;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequestItem;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.RequestItemAction;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 근태 신청서 상세 */
public record AttendanceRequestDetailResponse(
        Long attReqId,                      // 신청 ID
        AttendanceRequestType reqType,      // 신청 유형
        LocalDate targetDate,               // 대상일
        LocalDate substDate,                // 대체 휴일
        String reason,                      // 신청 사유
        AttendanceRequestStatus status,     // 결재 상태
        Long approvalDocId,                 // 결재문서 ID
        String docNo,                       // 문서번호
        OffsetDateTime createdAt,           // 상신 일시
        OffsetDateTime completedAt,         // 결재 완료 일시
        List<Item> items                    // 신청 상세
) {

    public record Item(
            Long itemId,                    // 상세 ID
            RequestItemAction action,       // 처리 구분
            Long targetLogId,               // 대상 로그 ID
            String statusCode,              // 근무 상태 코드
            String statusName,              // 근무 상태명
            OffsetDateTime startAt,         // 시작 시각
            OffsetDateTime endAt            // 종료 시각
    ) {
        public static Item from(AttendanceRequestItem item) {
            return new Item(
                    item.getItemId(),
                    item.getAction(),
                    item.getTargetLogId(),
                    item.getWorkStatusCode().getStatusCode(),
                    item.getWorkStatusCode().getName(),
                    DateTimeUtils.toSeoul(item.getStartAt()),
                    DateTimeUtils.toSeoul(item.getEndAt())
            );
        }
    }

    public static AttendanceRequestDetailResponse from(AttendanceRequest request) {
        return new AttendanceRequestDetailResponse(
                request.getAttReqId(),
                request.getReqType(),
                request.getTargetDate(),
                request.getSubstDate(),
                request.getReason(),
                request.getStatus(),
                request.getApprovalDocId(),
                request.getDocNo(),
                DateTimeUtils.toSeoul(request.getCreatedAt()),
                DateTimeUtils.toSeoul(request.getCompletedAt()),
                request.getItems().stream().map(Item::from).toList()
        );
    }
}
