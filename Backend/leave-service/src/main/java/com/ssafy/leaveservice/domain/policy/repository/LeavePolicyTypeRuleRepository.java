package com.ssafy.leaveservice.domain.policy.repository;

import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyTypeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LeavePolicyTypeRuleRepository extends JpaRepository<LeavePolicyTypeRule, Long> {

    List<LeavePolicyTypeRule> findAllByPolicyVersionIdOrderByLeaveTypeIdAsc(Long policyVersionId);

    @Query(value = """
            select * from leave_policy_type_rule where policy_version_id = :versionId
            order by leave_type_id for update
            """, nativeQuery = true)
    List<LeavePolicyTypeRule> findAllByPolicyVersionIdForUpdate(@Param("versionId") Long versionId);

    @Query(value = """
            select * from leave_policy_type_rule
            where policy_version_id = :versionId and leave_type_id = :leaveTypeId for update
            """, nativeQuery = true)
    Optional<LeavePolicyTypeRule> findByVersionIdAndLeaveTypeIdForUpdate(
            @Param("versionId") Long versionId,
            @Param("leaveTypeId") Long leaveTypeId
    );
}
