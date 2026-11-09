package com.ssafy.approval_service.domain.approval.model.entity;

import com.ssafy.approval_service.domain.approval.model.entity.enums.LineAction;
import com.ssafy.approval_service.domain.approval.model.entity.enums.LineRole;
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

/** 결재선 (approval_line) */
@Entity
@Table(name = "approval_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "line_id")
    private Long lineId;                // 결재선ID

    @Column(name = "step")
    private Short step;                 // 순서

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private LineRole role;              // 역할

    @Enumerated(EnumType.STRING)
    @Column(name = "action")
    private LineAction action;          // 동작수행

    @Column(name = "acted_at")
    private OffsetDateTime actedAt;     // 처리일시

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doc_id")
    private Document document;          // 결재 문서

    @Column(name = "emp_id")
    private Long empId;                 // 결재자 사원ID (user-service employee.emp_id)

    @Column(name = "required", nullable = false)
    private boolean required;           // 위임전결 규정상 결재자. true 면 제외·순서 변경·전결 건너뛰기 불가

    @Builder
    private ApprovalLine(Short step, LineRole role, Document document, Long empId, boolean required) {
        this.step = step;
        this.role = role;
        this.document = document;
        this.empId = empId;
        this.required = required;
        this.action = LineAction.PENDING;
    }

    public void agree() {
        this.action = LineAction.AGREE;
        this.actedAt = OffsetDateTime.now();
    }

    public void disagree() {
        this.action = LineAction.DISAGREE;
        this.actedAt = OffsetDateTime.now();
    }

    /** 앞 결재자의 전결로 결재 없이 넘어감 */
    public void skip() {
        this.action = LineAction.SKIPPED;
        this.actedAt = OffsetDateTime.now();
    }

    /** 결재 순서 변경. 결재가 끝난 결재선인지는 서비스에서 확인한다 */
    public void changeStep(Short step) {
        this.step = step;
    }

    /** 아직 결재하지 않은 결재선인지 */
    public boolean isPending() {
        return this.action == LineAction.PENDING;
    }
}
