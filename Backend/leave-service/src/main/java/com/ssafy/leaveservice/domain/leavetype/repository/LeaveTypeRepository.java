package com.ssafy.leaveservice.domain.leavetype.repository;

import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.leavetype.repository.projection.RequestableLeaveTypeSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LeaveTypeRepository extends JpaRepository<LeaveType, Long> {

    @Query("""
            select new com.ssafy.leaveservice.domain.leavetype.repository.projection.RequestableLeaveTypeSettings(
                leaveType.leaveTypeId, leaveType.name, leaveType.requestUnit, leaveType.maxMinutes,
                policyRule.allowedUnits, policyRule.minMinutes, policyRule.maxMinutes
            )
            from LeaveType leaveType
            join LeavePolicyTypeRule policyRule on policyRule.leaveTypeId = leaveType.leaveTypeId
            where policyRule.policyVersionId = :versionId
                and leaveType.active = true and policyRule.requestable = true
            order by leaveType.leaveTypeId asc
            """)
    List<RequestableLeaveTypeSettings> findRequestableSettingsByVersionId(@Param("versionId") Long versionId);

    @Query("select t from LeaveType t left join fetch t.balanceType order by t.leaveTypeId asc")
    List<LeaveType> findAllWithBalanceType();

    // FOR NO KEY UPDATE가 아닌 FOR UPDATE로 FK 참조 생성의 KEY SHARE 잠금과도 충돌시킨다.
    @Query(value = "select * from leave_type where leave_type_id = :leaveTypeId for update", nativeQuery = true)
    Optional<LeaveType> findByIdForUpdate(@Param("leaveTypeId") Long leaveTypeId);

    @Query(value = """
            select exists (select 1 from leave_request where leave_type_id = :leaveTypeId)
                or exists (select 1 from leave_policy_type_rule where leave_type_id = :leaveTypeId)
            """, nativeQuery = true)
    boolean existsReferences(@Param("leaveTypeId") Long leaveTypeId);
}
