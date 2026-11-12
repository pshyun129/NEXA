package com.ssafy.leaveservice.domain.request.dto;

// 시도 번호는 늦게 도착한 이전 HTTP 실패가 새 호출 상태를 덮어쓰지 않도록 사용한다.
public record LeaveApprovalCallStartResult(LeaveSubmissionResult request, boolean callRequired, Integer attemptCount) {
    public LeaveApprovalCallStartResult(LeaveSubmissionResult request, boolean callRequired) {
        this(request, callRequired, null);
    }
}
