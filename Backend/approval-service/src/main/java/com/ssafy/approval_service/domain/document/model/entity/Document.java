package com.ssafy.approval_service.domain.document.model.entity;

import com.ssafy.approval_service.domain.approval.policy.ApprovalAttributes;
import com.ssafy.approval_service.domain.approval.policy.ApprovalAttributesConverter;
import com.ssafy.approval_service.domain.approval.policy.ApprovalLevel;
import com.ssafy.approval_service.domain.document.model.entity.enums.DocumentStatus;
import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import com.ssafy.approval_service.domain.document.model.entity.enums.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** 결재 문서 (approval_document) */
@Entity
@Table(name = "approval_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doc_id")
    private Long docId;                     // 문서ID

    @Column(name = "doc_no", unique = true)
    private String docNo;                   // 문서번호. 저장 전에 서버가 채번해 채운다 (assignDocNo)

    @Enumerated(EnumType.STRING)
    @Column(name = "form_type")
    private FormType formType;              // 문서양식

    @Column(name = "title", length = 255)
    private String title;                   // 제목

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private DocumentStatus status;          // 결재상태

    @Column(name = "drafted_at")
    private OffsetDateTime draftedAt;       // 상신일시

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;     // 완료일시

    @Column(name = "drafter_id")
    private Long drafterId;                 // 기안자 사원ID (user-service employee.emp_id)

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;          // 출처 서비스

    @Column(name = "source_id")
    private Long sourceId;                  // 출처 서비스의 원본 신청 ID (DIRECT 면 null)

    @Column(name = "source_label", length = 100)
    private String sourceLabel;             // 출처 표시 이름 (상신 시점 스냅샷, 예: 워크플로우 이름). DIRECT 면 null

    @Column(name = "request_key", unique = true)
    private UUID requestKey;                // 생성 요청 키. 같은 키로 다시 요청하면 이 문서를 돌려준다

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_level", length = 20)
    private ApprovalLevel approvalLevel;    // 위임전결 규정상 전결 단계 (이 기능 전에 만든 문서는 null)

    @Convert(converter = ApprovalAttributesConverter.class)
    @Column(name = "attributes", columnDefinition = "TEXT")
    private ApprovalAttributes attributes;  // 전결 판단에 쓴 신청 값 (상신 시점 스냅샷, 없으면 null)

    @Builder
    private Document(FormType formType, String title, Long drafterId,
                     SourceType sourceType, Long sourceId, String sourceLabel, UUID requestKey,
                     ApprovalLevel approvalLevel, ApprovalAttributes attributes) {
        this.formType = formType;
        this.title = title;
        this.drafterId = drafterId;
        this.sourceType = sourceType == null ? SourceType.DIRECT : sourceType;
        this.sourceId = sourceId;
        this.sourceLabel = sourceLabel;
        this.requestKey = requestKey;
        this.approvalLevel = approvalLevel;
        this.attributes = attributes == null || attributes.isEmpty() ? null : attributes;
        this.status = DocumentStatus.INPROGRESS;
        this.draftedAt = OffsetDateTime.now();
    }

    /**
     * 같은 요청 키로 온 재요청이 이 문서를 만든 요청과 같은 신청인지.
     * 기안자·양식·출처가 같으면 같은 신청으로 본다 (제목·결재선은 재시도 중 바뀔 수 있어 비교하지 않음)
     */
    public boolean isSameRequest(Long drafterId, FormType formType, SourceType sourceType, Long sourceId) {
        return Objects.equals(this.drafterId, drafterId)
                && this.formType == formType
                && this.sourceType == sourceType
                && Objects.equals(this.sourceId, sourceId);
    }

    /** 문서번호 연도는 서버 시간대와 상관없이 한국 시간 기준 상신 연도 */
    private static final ZoneId NUMBERING_ZONE = ZoneId.of("Asia/Seoul");

    /** 문서번호를 만들 때 쓰는 연도 (한국 시간 기준 상신 연도) */
    public int numberingYear() {
        return draftedAt.atZoneSameInstant(NUMBERING_ZONE).getYear();
    }

    /**
     * 문서번호를 채운다. 형식: {양식}-{상신 연도}-{양식·연도별 일련번호 4자리} (예: LEAVE-2026-0001)
     * 한 번 정해지면 바뀌지 않는다 (재기안해도 유지)
     */
    public void assignDocNo(int sequence) {
        if (this.docNo != null) {
            throw new IllegalStateException("이미 문서번호가 있습니다: " + docNo);
        }
        this.docNo = "%s-%d-%04d".formatted(formType.name(), numberingYear(), sequence);
    }

    public void approve() {
        this.status = DocumentStatus.APPROVED;
        this.completedAt = OffsetDateTime.now();
    }

    public void reject() {
        this.status = DocumentStatus.REJECTED;
        this.completedAt = OffsetDateTime.now();
    }

    public void cancel() {
        this.status = DocumentStatus.CANCEL;
        this.completedAt = OffsetDateTime.now();
    }

    /** 반려된 문서를 다시 상신. 제목을 바꾸고 진행 중으로 되돌린다 */
    public void resubmit(String title) {
        this.title = title;
        this.status = DocumentStatus.INPROGRESS;
        this.draftedAt = OffsetDateTime.now();
        this.completedAt = null;
    }

    /** 반려된 문서인지 (재기안 가능 여부) */
    public boolean isRejected() {
        return this.status == DocumentStatus.REJECTED;
    }

    /** 다른 서비스(휴가·근태·업무 자동화)의 신청으로 만든 문서인지. 이런 문서는 반려가 최종이라 재기안할 수 없다 */
    public boolean isFromSource() {
        return this.sourceType != SourceType.DIRECT;
    }

    /** 해당 사원이 기안자인지 */
    public boolean isDraftedBy(Long employeeId) {
        return this.drafterId.equals(employeeId);
    }

    /** 결재 진행 중인지 (결재선 변경/결재 처리 가능 여부) */
    public boolean isInProgress() {
        return this.status == DocumentStatus.INPROGRESS;
    }
}
