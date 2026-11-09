package com.ssafy.leaveservice.domain.grant.entity;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
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

import java.time.OffsetDateTime;

@Entity
@Table(name = "leave_grant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveGrant {

    public static LeaveGrant manual(
            LeaveAccount account,
            Long policyVersionId,
            int minutes,
            OffsetDateTime validFrom,
            OffsetDateTime expiresOn,
            OffsetDateTime asOf
    ) {
        LeaveGrant grant = new LeaveGrant();
        grant.account = account;
        grant.policyVersionId = policyVersionId;
        grant.grantKind = GrantKind.MANUAL;
        grant.originalMinutes = minutes;
        grant.validFrom = validFrom;
        grant.expiresOn = expiresOn;
        grant.grantedOn = asOf;
        grant.createdAt = asOf;
        return grant;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "grant_id")
    private Long grantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private LeaveAccount account;

    @Column(name = "policy_version_id")
    private Long policyVersionId;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "parent_grant_id")
    private Long parentGrantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "grant_kind", nullable = false, length = 20)
    private GrantKind grantKind;

    @Column(name = "granted_on", nullable = false)
    private OffsetDateTime grantedOn;

    @Column(name = "original_minutes", nullable = false)
    private int originalMinutes;

    @Column(name = "valid_from", nullable = false)
    private OffsetDateTime validFrom;

    @Column(name = "expires_on")
    private OffsetDateTime expiresOn;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
