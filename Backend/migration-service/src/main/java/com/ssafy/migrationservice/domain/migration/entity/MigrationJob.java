package com.ssafy.migrationservice.domain.migration.entity;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationStatus;
import jakarta.persistence.Column;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "migration_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MigrationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "migration_id")
    private Long migrationId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;                 // 이관 대상 회사

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "cutover_date")
    private LocalDate cutoverDate;          // 전환 기준일

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MigrationStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private MigrationJob(Long companyId, String name, LocalDate cutoverDate) {
        this.companyId = companyId;
        this.name = name;
        this.cutoverDate = cutoverDate;
        this.status = MigrationStatus.DRAFT;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isCompleted() {
        return status == MigrationStatus.COMPLETED;
    }

    public void markLoaded() {
        this.status = MigrationStatus.LOADED;
    }

    /** 적재된 묶음이 모두 되돌려지면 다시 작성 중으로 */
    public void markDraft() {
        this.status = MigrationStatus.DRAFT;
    }
}
