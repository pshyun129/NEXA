package com.ssafy.leaveservice.domain.request.dto;

// callRequired=true인 최초 전이만 향후 외부 호출 대상이다. 재호출은 UUID를 재사용한다.
public record LeaveApprovalCallStartResult(LeaveSubmissionResult request, boolean callRequired) {
}
