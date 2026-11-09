package com.ssafy.user_service.domain.user.dto;

import com.ssafy.user_service.domain.user.entity.Duty;

/** 직책 조회 응답 */
public record DutyResponse(
        Long dutyId,
        String name
) {
    public static DutyResponse from(Duty duty) {
        return new DutyResponse(duty.getDutyId(), duty.getName());
    }
}
