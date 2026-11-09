package com.ssafy.leaveservice.domain.policy.repository;

import com.ssafy.leaveservice.domain.policy.entity.LeaveAccrualRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LeaveAccrualRuleRepository extends JpaRepository<LeaveAccrualRule, Long> {

    List<LeaveAccrualRule> findAllByPolicyVersionPolicyVersionIdOrderByRuleIdAsc(Long policyVersionId);

    @Query(value = """
            select * from leave_accrual_rule
            where policy_version_id = :versionId order by rule_id for update
            """, nativeQuery = true)
    List<LeaveAccrualRule> findAllByPolicyVersionIdForUpdate(@Param("versionId") Long versionId);
}
