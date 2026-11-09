package com.ssafy.approval_service.domain.approval.repository;

import com.ssafy.approval_service.domain.approval.model.entity.ApprovalHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalHistoryRepository extends JpaRepository<ApprovalHistory, Long> {

    /** 문서의 변경 이력을 발생 순서대로 조회 */
    List<ApprovalHistory> findByDocumentDocIdOrderByOccurredAtAsc(Long docId);

    /** 문서의 특정 차수 이력 조회 */
    List<ApprovalHistory> findByDocumentDocIdAndRoundOrderByOccurredAtAsc(Long docId, Integer round);

    /** 문서의 가장 최근 이력 (현재 차수 확인용) */
    Optional<ApprovalHistory> findTopByDocumentDocIdOrderByOccurredAtDesc(Long docId);
}
