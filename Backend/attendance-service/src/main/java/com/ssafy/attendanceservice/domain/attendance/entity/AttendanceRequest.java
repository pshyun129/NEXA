package com.ssafy.attendanceservice.domain.attendance.entity;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "attendance_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "att_req_id")
    private Long attReqId;                          // 신청 ID

    @Column(name = "emp_id", nullable = false)
    private Long empId;                             // 신청자

    @Enumerated(EnumType.STRING)
    @Column(name = "req_type", nullable = false)
    private AttendanceRequestType reqType;          // 신청 유형

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;                   // 대상일

    @Column(name = "subst_date")
    private LocalDate substDate;                    // 대체 휴일

    @Column(name = "reason", length = 500, nullable = false)
    private String reason;                          // 신청 사유

    @Column(name = "approval_doc_id")
    private Long approvalDocId;                     // 결재문서 ID

    @Column(name = "doc_no")
    private String docNo;                           // 문서번호 (결재 서비스가 채번, 예: OVERTIME-2026-0001)

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AttendanceRequestStatus status;         // 결재 상태

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;               // 상신 일자

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;             // 결재 완료 일자

    @OneToMany(mappedBy = "attendanceRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AttendanceRequestItem> items = new ArrayList<>();  // 신청 상세

    @Builder
    private AttendanceRequest(Long empId, AttendanceRequestType reqType, LocalDate targetDate,
                              LocalDate substDate, String reason) {
        this.empId = empId;
        this.reqType = reqType;
        this.targetDate = targetDate;
        this.substDate = substDate;
        this.reason = reason;
        this.status = AttendanceRequestStatus.PENDING;
    }

    /** 신청 상세 추가 (양방향 연관관계 설정) */
    public void addItem(AttendanceRequestItem item) {
        items.add(item);
        item.assignRequest(this);
    }

    /** 결재 문서 연결 (상신 시 결재 서비스가 만든 문서) */
    public void linkApprovalDocument(Long approvalDocId, String docNo) {
        this.approvalDocId = approvalDocId;
        this.docNo = docNo;
    }

    /** 결재 대기 중인가 */
    public boolean isPending() {
        return status == AttendanceRequestStatus.PENDING;
    }

    /** 결재 승인 */
    public void approve(OffsetDateTime completedAt) {
        this.status = AttendanceRequestStatus.APPROVED;
        this.completedAt = completedAt;
    }

    /** 결재 반려 (최종. 다시 하려면 새로 신청) */
    public void reject(OffsetDateTime completedAt) {
        this.status = AttendanceRequestStatus.REJECTED;
        this.completedAt = completedAt;
    }

    /** 결재 회수 */
    public void cancel(OffsetDateTime completedAt) {
        this.status = AttendanceRequestStatus.CANCELED;
        this.completedAt = completedAt;
    }
}
