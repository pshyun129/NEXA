package com.ssafy.user_service.domain.user.entity;


import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;
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

/**
 * 직군-직종-직무 코드 (FAMILY → KIND → ROLE 계층 구조)
 */
@Entity
@Table(name = "job_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_code_id")
    private Long jobCodeId;         // 코드ID

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private JobCodeType type;       // 종류

    @Column(name = "name", length = 255)
    private String name;            // 코드명

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private JobCode parent;         // 상위코드 (최상위 직군은 null)

    @Builder
    private JobCode(JobCodeType type, String name, JobCode parent) {
        this.type = type;
        this.name = name;
        this.parent = parent;
    }
}
