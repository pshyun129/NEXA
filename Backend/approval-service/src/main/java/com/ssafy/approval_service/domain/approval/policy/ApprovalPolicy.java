package com.ssafy.approval_service.domain.approval.policy;

import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 위임전결 규정: 양식과 신청 값으로 전결 단계(최종 결재권자)를 정한다.
 *
 * | 양식          | 기본   | 조건부 상향                                   |
 * | LEAVE         | 팀장   | 휴가 일수 ≥ 5일 → 본부장                       |
 * | OVERTIME      | 팀장   | 그 달 누적 > 12시간 → 본부장                   |
 * | TIME_FIX      | 팀장   | 없음                                          |
 * | HOLIDAY_SUBST | 팀장   | 없음                                          |
 * | BUSINESS_TRIP | 팀장   | 해외 → 본부장                                  |
 * | GENERAL       | 팀장   | 금액 > 100만 원 → 본부장, > 1,000만 원 → 대표이사 |
 *
 * 기준값은 ApprovalPolicyProperties(app.approval-policy.*). 급여는 결재 대상이 아니다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalPolicy {

    private final ApprovalPolicyProperties properties;

    public ApprovalLevel requiredLevel(FormType formType, ApprovalAttributes attributes) {
        ApprovalAttributes attrs = ApprovalAttributes.orEmpty(attributes);
        return switch (formType) {
            case LEAVE -> attrs.days() != null && attrs.days().compareTo(properties.leaveDivisionDays()) >= 0
                    ? ApprovalLevel.DIVISION : ApprovalLevel.TEAM;
            case OVERTIME -> attrs.monthlyOvertimeMinutes() != null
                    && attrs.monthlyOvertimeMinutes() > properties.overtimeDivisionMonthlyMinutes()
                    ? ApprovalLevel.DIVISION : ApprovalLevel.TEAM;
            case TIME_FIX, HOLIDAY_SUBST -> ApprovalLevel.TEAM;
            case BUSINESS_TRIP -> Boolean.TRUE.equals(attrs.overseas()) ? ApprovalLevel.DIVISION : ApprovalLevel.TEAM;
            case GENERAL -> generalLevel(attrs.amount());
        };
    }

    private ApprovalLevel generalLevel(Long amount) {
        if (amount == null) {
            return ApprovalLevel.TEAM;
        }
        if (amount > properties.generalCompanyAmount()) {
            return ApprovalLevel.COMPANY;
        }
        if (amount > properties.generalDivisionAmount()) {
            return ApprovalLevel.DIVISION;
        }
        return ApprovalLevel.TEAM;
    }
}
