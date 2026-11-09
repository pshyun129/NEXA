package com.ssafy.approval_service.domain.approval.model;

import com.ssafy.approval_service.domain.approval.model.entity.ApprovalLine;
import com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 현재 차례 규칙: 결재자(APPROVER/AGREEMENT) PENDING 줄 중 (step, lineId) 가 가장 앞인 한 줄 */
class ApprovalProgressTest {

    private static long lineSeq = 0;

    private static ApprovalLine line(long empId, Integer step, LineRole role) {
        ApprovalLine line = ApprovalLine.builder()
                .empId(empId)
                .step(step == null ? null : step.shortValue())
                .role(role)
                .build();
        ReflectionTestUtils.setField(line, "lineId", ++lineSeq);
        return line;
    }

    @Test
    void 첫_PENDING_결재자가_현재_차례이고_순번은_1부터() {
        ApprovalLine first = line(10, 1, LineRole.APPROVER);
        ApprovalLine second = line(20, 2, LineRole.AGREEMENT);

        ApprovalProgress progress = ApprovalProgress.of(true, List.of(second, first));

        assertThat(progress.currentApproverIds()).containsExactly(10L);
        assertThat(progress.currentStep()).isEqualTo(1);
        assertThat(progress.totalSteps()).isEqualTo(2);
        assertThat(progress.isTurnOf(10L)).isTrue();
        assertThat(progress.isTurnOf(20L)).isFalse();
    }

    @Test
    void 앞_결재자가_승인하면_다음_결재자_차례() {
        ApprovalLine first = line(10, 1, LineRole.APPROVER);
        ApprovalLine second = line(20, 2, LineRole.APPROVER);
        first.agree();

        ApprovalProgress progress = ApprovalProgress.of(true, List.of(first, second));

        assertThat(progress.currentApproverIds()).containsExactly(20L);
        assertThat(progress.currentStep()).isEqualTo(2);
    }

    @Test
    void 단계_번호가_비어_있어도_순번으로_센다() {
        ApprovalLine first = line(10, 1, LineRole.APPROVER);
        ApprovalLine added = line(20, 5, LineRole.APPROVER);
        first.agree();

        ApprovalProgress progress = ApprovalProgress.of(true, List.of(first, added));

        assertThat(progress.currentStep()).isEqualTo(2);
        assertThat(progress.totalSteps()).isEqualTo(2);
    }

    @Test
    void 참조자와_열람자는_차례와_단계_수에서_빠진다() {
        ApprovalLine referrer = line(30, 0, LineRole.REFERENCE);
        ApprovalLine viewer = line(40, null, LineRole.VIEWER);
        ApprovalLine approver = line(10, 1, LineRole.APPROVER);

        ApprovalProgress progress = ApprovalProgress.of(true, List.of(referrer, viewer, approver));

        assertThat(progress.currentApproverIds()).containsExactly(10L);
        assertThat(progress.totalSteps()).isEqualTo(1);
    }

    @Test
    void 같은_step이면_lineId가_작은_줄이_먼저() {
        ApprovalLine earlier = line(10, 1, LineRole.APPROVER);
        ApprovalLine later = line(20, 1, LineRole.AGREEMENT);

        assertThat(ApprovalProgress.currentTurn(List.of(later, earlier))).contains(earlier);
        assertThat(ApprovalProgress.of(true, List.of(later, earlier)).currentApproverIds()).containsExactly(10L);
    }

    @Test
    void 진행_중이_아니면_PENDING_줄이_남아_있어도_차례가_없다() {
        ApprovalLine rejecter = line(10, 1, LineRole.APPROVER);
        ApprovalLine untouched = line(20, 2, LineRole.APPROVER);
        rejecter.disagree();

        ApprovalProgress progress = ApprovalProgress.of(false, List.of(rejecter, untouched));

        assertThat(progress.currentApproverIds()).isEmpty();
        assertThat(progress.currentStep()).isNull();
        assertThat(progress.totalSteps()).isEqualTo(2);
        assertThat(progress.isTurnOf(20L)).isFalse();
    }

    @Test
    void 결재자가_없으면_빈_진행상황() {
        ApprovalProgress progress = ApprovalProgress.of(true, List.of());

        assertThat(progress.currentApproverIds()).isEmpty();
        assertThat(progress.currentStep()).isNull();
        assertThat(progress.totalSteps()).isZero();
    }
}
