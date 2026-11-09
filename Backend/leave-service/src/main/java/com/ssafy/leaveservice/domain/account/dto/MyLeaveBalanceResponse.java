package com.ssafy.leaveservice.domain.account.dto;

/**
 * 세 수량은 모두 조회 시점에 유효한 발생분만 대상으로 하며, 단위는 분이다.
 * quantityMinutes는 예약분을 포함한 장부 잔액으로 과거 전체 지급량이 아니다.
 * reservedMinutes는 예약량, remainingMinutes는 예약을 제외한 사용 가능 잔액이다.
 */
public record MyLeaveBalanceResponse(
        Long accountId,
        Long balanceTypeId,
        String balanceTypeName,
        long quantityMinutes,
        long reservedMinutes,
        long remainingMinutes
) {
}
