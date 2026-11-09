package com.ssafy.approval_service.domain.approval.model;

import com.ssafy.approval_service.domain.approval.model.entity.ApprovalLine;
import com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 결재 진행 상황과 "현재 차례" 규칙을 한 곳에 둔다.
 * 순차 결재: 결재자(APPROVER/AGREEMENT) 중 PENDING 이고 (step, lineId) 순으로 가장 앞인 한 줄이 현재 차례다.
 * 결재 처리 검증(ApprovalServiceImplV1), 목록 항목(DocumentSummaryResponse), 결재 대기함 쿼리
 * (DocumentRepository.findPendingFor) 가 모두 이 규칙을 따른다. 쿼리를 바꾸면 여기와 같이 바꿔야 한다.
 *
 * @param currentApproverIds 현재 차례인 사원 ID. 순차 결재라 지금은 최대 1명이지만, 병렬 합의를 대비해 배열로 둔다.
 *                           진행 중이 아니면 빈 배열
 * @param currentStep        현재 차례가 결재자 중 몇 번째인지 (1부터). step 값은 중간이 비어 있을 수 있어 순번으로 센다.
 *                           진행 중이 아니면 null
 * @param totalSteps         결재자(APPROVER/AGREEMENT) 수. 전결로 생략된 줄도 센다
 */
public record ApprovalProgress(List<Long> currentApproverIds, Integer currentStep, int totalSteps) {

    public static final Set<LineRole> APPROVER_ROLES = Set.of(LineRole.APPROVER, LineRole.AGREEMENT);

    private static final Comparator<ApprovalLine> LINE_ORDER = Comparator
            .comparing(ApprovalLine::getStep, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ApprovalLine::getLineId, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * @param inProgress 문서가 진행 중인지. 반려·취소된 문서에도 PENDING 줄이 남아 있으므로 차례는 진행 중일 때만 계산한다
     * @param lines      문서의 결재선 (역할·순서 무관, 참조자/열람자가 섞여 있어도 된다)
     */
    public static ApprovalProgress of(boolean inProgress, List<ApprovalLine> lines) {
        List<ApprovalLine> approvers = orderedApprovers(lines);
        if (!inProgress) {
            return new ApprovalProgress(List.of(), null, approvers.size());
        }
        for (int i = 0; i < approvers.size(); i++) {
            ApprovalLine line = approvers.get(i);
            if (line.isPending()) {
                return new ApprovalProgress(List.of(line.getEmpId()), i + 1, approvers.size());
            }
        }
        return new ApprovalProgress(List.of(), null, approvers.size());
    }

    /** 현재 차례인 결재선. 결재자가 모두 처리했으면 empty (문서 상태는 따로 확인해야 한다) */
    public static Optional<ApprovalLine> currentTurn(List<ApprovalLine> lines) {
        return orderedApprovers(lines).stream()
                .filter(ApprovalLine::isPending)
                .findFirst();
    }

    public boolean isTurnOf(Long employeeId) {
        return currentApproverIds.contains(employeeId);
    }

    private static List<ApprovalLine> orderedApprovers(List<ApprovalLine> lines) {
        return lines.stream()
                .filter(line -> APPROVER_ROLES.contains(line.getRole()))
                .sorted(LINE_ORDER)
                .toList();
    }
}
