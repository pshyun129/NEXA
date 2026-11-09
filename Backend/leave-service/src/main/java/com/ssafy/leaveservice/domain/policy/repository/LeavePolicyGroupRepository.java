package com.ssafy.leaveservice.domain.policy.repository;

import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface LeavePolicyGroupRepository extends JpaRepository<LeavePolicyGroup, Long> {

    List<LeavePolicyGroup> findAllByOrderByPolicyIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select policyGroup from LeavePolicyGroup policyGroup where policyGroup.policyId = :policyId")
    Optional<LeavePolicyGroup> findByIdForUpdate(@Param("policyId") Long policyId);

    // 배정은 포인터를 읽기만 하므로 직원 간 공유하고, 활성화의 쓰기 잠금과는 충돌시킨다.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select policyGroup from LeavePolicyGroup policyGroup where policyGroup.policyId = :policyId")
    Optional<LeavePolicyGroup> findByIdForAssignment(@Param("policyId") Long policyId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            update leave_policy_group set active_policy_version_id = :versionId
            where policy_id = :policyId
            """, nativeQuery = true)
    int updateActiveVersion(@Param("policyId") Long policyId, @Param("versionId") Long versionId);
}
