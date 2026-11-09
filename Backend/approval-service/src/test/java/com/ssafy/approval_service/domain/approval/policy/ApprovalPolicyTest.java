package com.ssafy.approval_service.domain.approval.policy;

import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 위임전결 규정: 양식·신청 값 → 전결 단계. 기준값은 운영 기본값과 같다 (5일, 720분, 100만, 1,000만) */
class ApprovalPolicyTest {

    private final ApprovalPolicy policy = new ApprovalPolicy(
            new ApprovalPolicyProperties(new BigDecimal("5"), 720, 1_000_000L, 10_000_000L));

    private static ApprovalAttributes days(String days) {
        return new ApprovalAttributes(new BigDecimal(days), null, null, null, null);
    }

    private static ApprovalAttributes monthlyOvertime(int minutes) {
        return new ApprovalAttributes(null, 60, minutes, null, null);
    }

    private static ApprovalAttributes overseas(Boolean overseas) {
        return new ApprovalAttributes(null, null, null, overseas, null);
    }

    private static ApprovalAttributes amount(long amount) {
        return new ApprovalAttributes(null, null, null, null, amount);
    }

    @ParameterizedTest
    @EnumSource(FormType.class)
    void 신청_값이_없으면_모든_양식이_팀장_전결(FormType formType) {
        assertThat(policy.requiredLevel(formType, null)).isEqualTo(ApprovalLevel.TEAM);
        assertThat(policy.requiredLevel(formType, ApprovalAttributes.EMPTY)).isEqualTo(ApprovalLevel.TEAM);
    }

    @Test
    void 휴가는_5일_이상이면_본부장() {
        assertThat(policy.requiredLevel(FormType.LEAVE, days("4.5"))).isEqualTo(ApprovalLevel.TEAM);
        assertThat(policy.requiredLevel(FormType.LEAVE, days("5"))).isEqualTo(ApprovalLevel.DIVISION);
        assertThat(policy.requiredLevel(FormType.LEAVE, days("10"))).isEqualTo(ApprovalLevel.DIVISION);
    }

    @Test
    void 초과근무는_월_누적_12시간을_넘으면_본부장() {
        assertThat(policy.requiredLevel(FormType.OVERTIME, monthlyOvertime(720))).isEqualTo(ApprovalLevel.TEAM);
        assertThat(policy.requiredLevel(FormType.OVERTIME, monthlyOvertime(721))).isEqualTo(ApprovalLevel.DIVISION);
    }

    @Test
    void 근무시간_수정과_휴일대체는_조건_없이_팀장() {
        ApprovalAttributes everything = new ApprovalAttributes(new BigDecimal("30"), 9999, 9999, true, 999_999_999L);
        assertThat(policy.requiredLevel(FormType.TIME_FIX, everything)).isEqualTo(ApprovalLevel.TEAM);
        assertThat(policy.requiredLevel(FormType.HOLIDAY_SUBST, everything)).isEqualTo(ApprovalLevel.TEAM);
    }

    @Test
    void 출장은_해외면_본부장() {
        assertThat(policy.requiredLevel(FormType.BUSINESS_TRIP, overseas(false))).isEqualTo(ApprovalLevel.TEAM);
        assertThat(policy.requiredLevel(FormType.BUSINESS_TRIP, overseas(true))).isEqualTo(ApprovalLevel.DIVISION);
    }

    @Test
    void 일반_기안은_금액으로_본부장_대표이사() {
        assertThat(policy.requiredLevel(FormType.GENERAL, amount(1_000_000))).isEqualTo(ApprovalLevel.TEAM);
        assertThat(policy.requiredLevel(FormType.GENERAL, amount(1_000_001))).isEqualTo(ApprovalLevel.DIVISION);
        assertThat(policy.requiredLevel(FormType.GENERAL, amount(10_000_000))).isEqualTo(ApprovalLevel.DIVISION);
        assertThat(policy.requiredLevel(FormType.GENERAL, amount(10_000_001))).isEqualTo(ApprovalLevel.COMPANY);
    }

    @Test
    void 다른_양식의_신청_값은_무시한다() {
        // 휴가 일수가 커도 출장에는 영향 없음
        assertThat(policy.requiredLevel(FormType.BUSINESS_TRIP, days("30"))).isEqualTo(ApprovalLevel.TEAM);
        // 금액이 커도 휴가에는 영향 없음
        assertThat(policy.requiredLevel(FormType.LEAVE, amount(999_999_999L))).isEqualTo(ApprovalLevel.TEAM);
    }
}
