package com.ssafy.leaveservice.domain.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "leave_policy_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeavePolicyGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_id")
    private Long policyId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    // 활성 포인터는 Repository의 전용 SQL로 변경해 이름 수정 시 덮어쓰지 않는다.
    @Column(name = "active_policy_version_id", insertable = false, updatable = false)
    private Long activePolicyVersionId;

    public LeavePolicyGroup(String name) {
        this.name = name;
    }

    public void updateName(String name) {
        this.name = name;
    }
}
