package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record LeaveRequestPreviewResponse(
        OffsetDateTime asOf,
        Long leaveTypeId,
        Long policyVersionId,
        boolean requestable,
        ValidationStatus validationStatus,
        HolidayVerification holidayVerification,
        int totalRequestedMinutes,
        int balanceDeductionMinutes,
        boolean balanceRequired,
        Long remainingMinutes,
        List<DateResponse> dates,
        List<Reason> reasons
) {
    public enum ValidationStatus { REQUESTABLE, NOT_REQUESTABLE, UNVERIFIABLE }
    public enum HolidayVerification { VERIFIED, UNAVAILABLE }
    public record Reason(String code, LocalDate date, String message) {}
    public record Allocation(Long grantId, long minutes) {}
    public record DateResponse(
            LocalDate leaveDate,
            LeaveRequestPortion portion,
            int requestedMinutes,
            int balanceDeductionMinutes,
            List<Allocation> allocations
    ) {}
}
