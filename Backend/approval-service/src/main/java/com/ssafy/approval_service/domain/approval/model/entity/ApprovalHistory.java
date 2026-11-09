package com.ssafy.approval_service.domain.approval.model.entity;

import com.ssafy.approval_service.domain.approval.model.entity.enums.HistoryAction;
import com.ssafy.approval_service.domain.document.model.entity.Document;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** 결재 변경 이력 (approval_history) */
@Entity
@Table(name = "approval_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;             // 이력ID

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doc_id", nullable = false)
    private Document document;          // 결재 문서

    @Column(name = "round")
    private Integer round;              // 재기안 차수

    @Column(name = "actor_id", nullable = false)
    private Long actorId;               // 행위자 사원ID

    @Enumerated(EnumType.STRING)
    @Column(name = "action")
    private HistoryAction action;       // 행위

    @Column(name = "target_emp_id")
    private Long targetEmpId;           // 결재자 추가/제거 대상 사원ID

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;             // 반려 사유

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;  // 발생일시

    @Builder
    private ApprovalHistory(Document document, Integer round, Long actorId, HistoryAction action,
                            Long targetEmpId, String comment) {
        this.document = document;
        this.round = round;
        this.actorId = actorId;
        this.action = action;
        this.targetEmpId = targetEmpId;
        this.comment = comment;
        this.occurredAt = OffsetDateTime.now();
    }
}
