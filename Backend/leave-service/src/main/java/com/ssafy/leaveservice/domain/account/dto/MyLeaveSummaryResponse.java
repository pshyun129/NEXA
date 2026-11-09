package com.ssafy.leaveservice.domain.account.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 현재 유효 발생분의 잔액만 제공하는 요약 API 1차 응답이다. */
public record MyLeaveSummaryResponse(
        OffsetDateTime asOf,
        List<MyLeaveBalanceResponse> balances
) {
}
