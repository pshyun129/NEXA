package com.ssafy.leaveservice.domain.policy.repository;

import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface LeavePolicyVersionRepository extends JpaRepository<LeavePolicyVersion, Long> {

    List<LeavePolicyVersion> findAllByPolicyGroupPolicyIdOrderByVersionNoAsc(Long policyId);

    Optional<LeavePolicyVersion> findByPolicyVersionIdAndPolicyGroupPolicyId(Long versionId, Long policyId);

    @Query("select coalesce(max(version.versionNo), 0) from LeavePolicyVersion version where version.policyGroup.policyId = :policyId")
    int findMaxVersionNoByPolicyId(@Param("policyId") Long policyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select version from LeavePolicyVersion version
            where version.policyVersionId = :versionId and version.policyGroup.policyId = :policyId
            """)
    Optional<LeavePolicyVersion> findByIdAndPolicyIdForUpdate(
            @Param("versionId") Long versionId,
            @Param("policyId") Long policyId
    );

    @Query(value = """
            select exists (select 1 from leave_request where policy_version_id = :versionId)
                or exists (
                    select 1 from leave_grant grant_record
                    left join leave_accrual_rule rule on rule.rule_id = grant_record.rule_id
                    where grant_record.policy_version_id = :versionId or rule.policy_version_id = :versionId
                )
            """, nativeQuery = true)
    boolean existsUsageHistory(@Param("versionId") Long versionId);

    // FK 검사의 KEY SHARE와 충돌하도록 삭제 경로는 PostgreSQL FOR UPDATE를 명시한다.
    @Query(value = """
            select * from leave_policy_version
            where policy_version_id = :versionId and policy_id = :policyId for update
            """, nativeQuery = true)
    Optional<LeavePolicyVersion> findByIdAndPolicyIdForDelete(
            @Param("versionId") Long versionId,
            @Param("policyId") Long policyId
    );

    @Query(value = """
            select rule_id from leave_accrual_rule
            where policy_version_id = :versionId order by rule_id for update
            """, nativeQuery = true)
    List<Long> lockAccrualRules(@Param("versionId") Long versionId);

    @Query(value = """
            select rule_id from leave_policy_type_rule
            where policy_version_id = :versionId order by rule_id for update
            """, nativeQuery = true)
    List<Long> lockTypeRules(@Param("versionId") Long versionId);

    @Modifying
    @Query(value = "delete from leave_policy_type_rule where policy_version_id = :versionId", nativeQuery = true)
    int deleteTypeRules(@Param("versionId") Long versionId);

    @Modifying
    @Query(value = "delete from leave_accrual_rule where policy_version_id = :versionId", nativeQuery = true)
    int deleteAccrualRules(@Param("versionId") Long versionId);
}
