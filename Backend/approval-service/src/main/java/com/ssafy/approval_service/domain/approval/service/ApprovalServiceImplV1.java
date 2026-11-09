package com.ssafy.approval_service.domain.approval.service;

import com.ssafy.approval_service.domain.approval.model.ApprovalProgress;
import com.ssafy.approval_service.domain.approval.model.dto.ApprovalHistoryResponse;
import com.ssafy.approval_service.domain.approval.model.dto.ResubmitRequest;
import com.ssafy.approval_service.domain.approval.model.entity.ApprovalHistory;
import com.ssafy.approval_service.domain.approval.model.entity.ApprovalLine;
import com.ssafy.approval_service.domain.approval.model.entity.enums.HistoryAction;
import com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole;
import com.ssafy.approval_service.domain.approval.repository.ApprovalHistoryRepository;
import com.ssafy.approval_service.domain.approval.policy.ApprovalLevel;
import com.ssafy.approval_service.domain.approval.policy.ApprovalLineGenerator;
import com.ssafy.approval_service.domain.approval.policy.ApprovalPolicy;
import com.ssafy.approval_service.domain.approval.policy.GeneratedApprovalLine;
import com.ssafy.approval_service.domain.approval.repository.ApprovalLineRepository;
import com.ssafy.approval_service.domain.document.model.entity.Document;
import com.ssafy.approval_service.domain.document.service.DocumentService;
import com.ssafy.approval_service.global.exception.BusinessException;
import com.ssafy.approval_service.global.exception.ErrorCode;
import com.ssafy.nexa.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 결재 처리. 순차 결재: PENDING 결재자(APPROVER/AGREEMENT) 중 step이 가장 작은 사람이 현재 차례다.
 * 합의자의 비동의는 반려로 처리하고, 전결은 차례인 APPROVER만 할 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApprovalServiceImplV1 implements ApprovalService {

    private static final List<LineRole> APPROVER_ROLES = List.of(LineRole.APPROVER, LineRole.AGREEMENT);
    private static final int FIRST_ROUND = 1;

    private final ApprovalLineRepository approvalLineRepository;
    private final ApprovalHistoryRepository approvalHistoryRepository;
    private final DocumentService documentService;
    private final ApprovalPolicy approvalPolicy;
    private final ApprovalLineGenerator approvalLineGenerator;
    private final DocumentCompleter documentCompleter;

    @Override
    public List<ApprovalHistoryResponse> getHistories(Long documentId, AuthUser requester) {
        documentService.getReadableDocument(documentId, requester);
        return approvalHistoryRepository.findByDocumentDocIdOrderByOccurredAtAsc(documentId).stream()
                .map(ApprovalHistoryResponse::from)
                .toList();
    }

    // ===== 결재자 =====

    @Override
    @Transactional
    public void approve(Long employeeId, Long documentId) {
        Document document = getInProgressDocument(documentId);
        List<ApprovalLine> approvers = approvers(documentId);
        ApprovalLine myLine = getMyTurn(approvers, employeeId);

        myLine.agree();
        // 남은 결재자가 없으면 최종 승인
        if (approvers.stream().noneMatch(ApprovalLine::isPending)) {
            documentCompleter.approve(document);
        }
        recordHistory(document, currentRound(documentId), employeeId, HistoryAction.APPROVE, null);
    }

    @Override
    @Transactional
    public void reject(Long employeeId, Long documentId, String comment) {
        Document document = getInProgressDocument(documentId);
        ApprovalLine myLine = getMyTurn(approvers(documentId), employeeId);

        myLine.disagree();
        documentCompleter.reject(document);
        recordHistory(document, currentRound(documentId), employeeId, HistoryAction.REJECT, comment);
    }

    @Override
    @Transactional
    public void finalApprove(Long employeeId, Long documentId) {
        Document document = getInProgressDocument(documentId);
        List<ApprovalLine> approvers = approvers(documentId);
        ApprovalLine myLine = getMyTurn(approvers, employeeId);
        if (myLine.getRole() != LineRole.APPROVER) {
            throw new BusinessException(ErrorCode.FINAL_APPROVE_NOT_ALLOWED);
        }
        // 규정상 결재자(전결권자까지)는 건너뛸 수 없다. 기안자가 추가한 결재자만 전결로 생략된다
        if (approvers.stream().anyMatch(line -> line != myLine && line.isPending() && line.isRequired())) {
            throw new BusinessException(ErrorCode.REQUIRED_APPROVER_NOT_SKIPPABLE);
        }

        myLine.agree();
        // 뒤에 남은 결재자는 지우지 않고 생략 처리 (누가 건너뛰어졌는지 남김)
        approvers.stream()
                .filter(ApprovalLine::isPending)
                .forEach(ApprovalLine::skip);
        documentCompleter.approve(document);
        recordHistory(document, currentRound(documentId), employeeId, HistoryAction.FINAL_APPROVE, null);
    }

    // ===== 기안자 =====

    @Override
    @Transactional
    public void cancel(Long employeeId, Long documentId) {
        Document document = getInProgressDocument(documentId);
        validateDrafter(document, employeeId);
        // 결재자가 한 명이라도 처리했으면 회수 불가
        if (approvers(documentId).stream().anyMatch(line -> !line.isPending())) {
            throw new BusinessException(ErrorCode.ALREADY_ACTED, "결재가 진행된 문서는 회수할 수 없습니다.");
        }

        documentCompleter.cancel(document);
        recordHistory(document, currentRound(documentId), employeeId, HistoryAction.CANCEL, null);
    }

    @Override
    @Transactional
    public void resubmit(Long employeeId, Long documentId, ResubmitRequest request) {
        // 반려 문서는 결재·결재선 변경 요청이 처리할 일이 없어(진행 중 문서만 받음), 잠근 채로 조직도를 조회해도 기다리는 요청이 없다
        Document document = documentService.getDocumentForUpdate(documentId);
        validateDrafter(document, employeeId);
        // 출처 문서는 반려가 최종 (결과 이벤트를 이미 받은 원본 서비스와 어긋나지 않게). 다시 하려면 원본에서 새로 신청
        if (document.isFromSource()) {
            throw new BusinessException(ErrorCode.SOURCE_DOCUMENT_NOT_RESUBMITTABLE);
        }
        if (!document.isRejected()) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_REJECTED);
        }

        // 결재선은 규정과 지금 조직도로 다시 만든다 (그 사이 부서장이 바뀌었을 수 있음).
        // 이전 차수의 결재 기록은 이력에 남아 있으므로 결재선은 통째로 바꾼다
        ApprovalLevel level = approvalPolicy.requiredLevel(document.getFormType(), document.getAttributes());
        GeneratedApprovalLine generated = approvalLineGenerator.generate(employeeId, level);
        approvalLineRepository.deleteByDocumentDocId(documentId);
        approvalLineRepository.saveAll(generated.toEntities(document));

        int round = currentRound(documentId) + 1;
        document.resubmit(request.title());
        recordHistory(document, round, employeeId, HistoryAction.RESUBMIT, null);

        // 그 사이 기안자가 최상위 부서장이 됐으면 상신과 같이 자가결재
        if (generated.selfApproval()) {
            documentCompleter.approve(document);
            recordHistory(document, round, employeeId, HistoryAction.FINAL_APPROVE, null);
        }
    }

    // ===== 공통 =====

    /** 진행 중인 문서를 잠가서 조회. 같은 문서의 동시 요청은 순서대로 처리된다 */
    private Document getInProgressDocument(Long documentId) {
        Document document = documentService.getDocumentForUpdate(documentId);
        if (!document.isInProgress()) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_IN_PROGRESS);
        }
        return document;
    }

    private void validateDrafter(Document document, Long employeeId) {
        if (!document.isDraftedBy(employeeId)) {
            throw new BusinessException(ErrorCode.NOT_DRAFTER);
        }
    }

    /** 현재 차례(ApprovalProgress 규칙)가 요청자인지 확인하고 그 결재선을 반환 */
    private ApprovalLine getMyTurn(List<ApprovalLine> approvers, Long employeeId) {
        ApprovalLine current = ApprovalProgress.currentTurn(approvers)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_IN_PROGRESS));
        if (!current.getEmpId().equals(employeeId)) {
            throw new BusinessException(ErrorCode.NOT_YOUR_TURN);
        }
        return current;
    }

    /** 결재자/합의자를 step 순으로 조회 (참조자/열람자 제외) */
    private List<ApprovalLine> approvers(Long documentId) {
        return approvalLineRepository.findByDocumentDocIdAndRoleInOrderByStepAscLineIdAsc(documentId, APPROVER_ROLES);
    }

    /** 가장 최근 이력의 차수 */
    private int currentRound(Long documentId) {
        return approvalHistoryRepository.findTopByDocumentDocIdOrderByOccurredAtDesc(documentId)
                .map(ApprovalHistory::getRound)
                .orElse(FIRST_ROUND);
    }

    private void recordHistory(Document document, int round, Long actorId, HistoryAction action, String comment) {
        approvalHistoryRepository.save(ApprovalHistory.builder()
                .document(document)
                .round(round)
                .actorId(actorId)
                .action(action)
                .comment(comment)
                .build());
    }
}
