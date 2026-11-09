package com.ssafy.leaveservice.domain.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Table(name = "leave_policy_type_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeavePolicyTypeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    // 이번 조회는 FK ID와 규칙 설정만 사용하므로 연관 테이블을 로딩하지 않는다.
    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @Column(name = "leave_type_id", nullable = false)
    private Long leaveTypeId;

    @Column(name = "is_requestable", nullable = false)
    private boolean requestable;

    @Enumerated(EnumType.STRING)
    @Column(name = "allowed_units", nullable = false, length = 30)
    private PolicyAllowedUnits allowedUnits;

    @Column(name = "min_minutes")
    private Integer minMinutes;

    @Column(name = "max_minutes")
    private Integer maxMinutes;

    public LeavePolicyTypeRule(
            Long policyVersionId,
            Long leaveTypeId,
            boolean requestable,
            PolicyAllowedUnits allowedUnits,
            Integer minMinutes,
            Integer maxMinutes
    ) {
        this.policyVersionId = policyVersionId;
        this.leaveTypeId = leaveTypeId;
        updateSettings(requestable, allowedUnits, minMinutes, maxMinutes);
    }

    public void updateSettings(
            boolean requestable,
            PolicyAllowedUnits allowedUnits,
            Integer minMinutes,
            Integer maxMinutes
    ) {
        this.requestable = requestable;
        this.allowedUnits = allowedUnits;
        this.minMinutes = minMinutes;
        this.maxMinutes = maxMinutes;
    }
}
