package com.ssafy.attendanceservice.domain.attendance.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 결재 문서 생성 요청 (approval-service POST /api/v1/documents, DOCS/approval-document-source.md).
 * 결재선은 보내지 않는다. 결재 서비스가 양식과 attributes(전결 판단 값)로 위임전결 규정에 따라 만든다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalDocumentCreateRequest(
        String title,               // 제목
        String formType,            // 문서 양식 (근태 신청 종류와 같은 이름)
        String sourceType,          // 문서 출처
        Long sourceId,              // 원본 근태 신청 ID
        UUID requestKey,            // 생성 요청 키. 같은 키로 다시 요청하면 처음 만든 문서를 돌려준다
        Attributes attributes       // 전결 판단 값 (초과근무만, 그 외는 보내지 않음)
) {

    private static final String SOURCE_ATTENDANCE = "ATTENDANCE";

    /** 전결 판단 값. 그 달 누적 초과근무가 기준을 넘으면 본부장까지 결재가 올라간다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attributes(
            Integer overtimeMinutes,            // 이번 초과근무 신청 시간(분)
            Integer monthlyOvertimeMinutes      // 이번 신청을 포함한 그 달 초과근무 누적(분)
    ) {
    }

    /** 근태 신청서의 결재 문서 */
    public static ApprovalDocumentCreateRequest ofAttendance(String title, AttendanceRequestType reqType,
                                                             Long attReqId, Attributes attributes) {
        return new ApprovalDocumentCreateRequest(title, reqType.name(), SOURCE_ATTENDANCE, attReqId,
                requestKeyOf(attReqId), attributes);
    }

    /** 근태 신청의 결재 요청 키. 신청 ID로 계산해 항상 같은 값이 나온다 (저장하지 않음) */
    public static UUID requestKeyOf(Long attReqId) {
        return UUID.nameUUIDFromBytes((SOURCE_ATTENDANCE + "-" + attReqId).getBytes(StandardCharsets.UTF_8));
    }
}
