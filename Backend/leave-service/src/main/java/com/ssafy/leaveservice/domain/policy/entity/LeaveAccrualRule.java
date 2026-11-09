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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Table(name = "leave_accrual_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveAccrualRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_version_id", nullable = false)
    private LeavePolicyVersion policyVersion;

    @Column(name = "min_service_months", nullable = false)
    private int minServiceMonths;

    @Column(name = "max_service_months")
    private Integer maxServiceMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private AccrualFrequency frequency;

    @Column(name = "grant_minutes", nullable = false)
    private int grantMinutes;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    public LeaveAccrualRule(LeavePolicyVersion policyVersion, LeaveAccrualRuleSettings settings) {
        this.policyVersion = policyVersion;
        updateSettings(settings);
    }

    public LeaveAccrualRuleSettings getSettings() {
        return new LeaveAccrualRuleSettings(minServiceMonths, maxServiceMonths, frequency, grantMinutes, active);
    }

    public void updateSettings(LeaveAccrualRuleSettings settings) {
        this.minServiceMonths = settings.minServiceMonths();
        this.maxServiceMonths = settings.maxServiceMonths();
        this.frequency = settings.frequency();
        this.grantMinutes = settings.grantMinutes();
        this.active = settings.active();
    }
}
