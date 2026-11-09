package com.ssafy.leaveservice.domain.leavetype.dto;

import com.ssafy.leaveservice.domain.leavetype.entity.BalanceMode;
import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LeaveTypeCreateRequest(
        Long balanceTypeId,
        @NotBlank(message = "휴가 유형명은 필수입니다.")
        @Size(max = 100, message = "휴가 유형명은 100자 이하여야 합니다.")
        String name,
        @NotNull(message = "유급 여부는 필수입니다.") Boolean isPaid,
        @NotNull(message = "잔액 차감 방식은 필수입니다.") BalanceMode balanceMode,
        @NotNull(message = "신청 단위는 필수입니다.") RequestUnit requestUnit,
        @Positive(message = "최대 시간은 양수여야 합니다.") Integer maxMinutes,
        Boolean isActive
) {
    public LeaveTypeCreateRequest {
        isActive = isActive == null ? Boolean.TRUE : isActive;
    }
}
