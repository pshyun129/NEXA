package com.ssafy.leaveservice.domain.policy.entity;

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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

@Entity
@Table(name = "leave_policy_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeavePolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_version_id")
    private Long policyVersionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private LeavePolicyGroup policyGroup;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "effective_from")
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_type", nullable = false, length = 20)
    private PolicyBaseType baseType;

    @Column(name = "fiscal_start", length = 5)
    private String fiscalStart;

    @Column(name = "unit_minutes", nullable = false)
    private int unitMinutes;

    @Column(name = "carry_over_allowed", nullable = false)
    private boolean carryOverAllowed;

    @Column(name = "carry_over_limit_minutes")
    private Integer carryOverLimitMinutes;

    @Column(name = "expiry_rule", nullable = false, length = 50)
    private String expiryRule;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public void updateSettings(LeavePolicyVersionSettings settings) {
        this.baseType = settings.baseType();
        this.fiscalStart = settings.fiscalStart();
        this.unitMinutes = settings.unitMinutes();
        this.carryOverAllowed = settings.carryOverAllowed();
        this.carryOverLimitMinutes = settings.carryOverLimitMinutes();
        this.expiryRule = settings.expiryRule();
        this.effectiveFrom = settings.effectiveFrom();
        this.effectiveTo = settings.effectiveTo();
    }

    @Builder
    private LeavePolicyVersion(
            LeavePolicyGroup policyGroup,
            int versionNo,
            PolicyBaseType baseType,
            String fiscalStart,
            int unitMinutes,
            boolean carryOverAllowed,
            Integer carryOverLimitMinutes,
            String expiryRule,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo
    ) {
        this.policyGroup = policyGroup;
        this.versionNo = versionNo;
        this.baseType = baseType;
        this.fiscalStart = fiscalStart;
        this.unitMinutes = unitMinutes;
        this.carryOverAllowed = carryOverAllowed;
        this.carryOverLimitMinutes = carryOverLimitMinutes;
        this.expiryRule = expiryRule;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }
}
