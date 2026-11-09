package com.ssafy.approval_service.domain.approval.policy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * 위임전결 규정의 조건부 상향 기준값 (app.approval-policy.*). 코드 수정 없이 설정으로 바꾼다.
 *
 * @param leaveDivisionDays              휴가 일수가 이 값 이상이면 본부장 전결
 * @param overtimeDivisionMonthlyMinutes 그 달 초과근무 누적(분)이 이 값을 넘으면 본부장 전결
 * @param generalDivisionAmount          일반 기안 금액(원)이 이 값을 넘으면 본부장 전결
 * @param generalCompanyAmount           일반 기안 금액(원)이 이 값을 넘으면 대표이사 결재
 */
@Validated
@ConfigurationProperties("app.approval-policy")
public record ApprovalPolicyProperties(
        @NotNull @Positive BigDecimal leaveDivisionDays,
        @NotNull @Positive Integer overtimeDivisionMonthlyMinutes,
        @NotNull @Positive Long generalDivisionAmount,
        @NotNull @Positive Long generalCompanyAmount
) {
}
