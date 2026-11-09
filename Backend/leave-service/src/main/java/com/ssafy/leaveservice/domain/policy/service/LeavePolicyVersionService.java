package com.ssafy.leaveservice.domain.policy.service;

import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionListResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionDetailResponse;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionCreateRequest;
import com.ssafy.leaveservice.domain.policy.dto.LeavePolicyVersionUpdateRequest;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersionSettings;
import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyGroup;
import com.ssafy.leaveservice.domain.policy.entity.LeavePolicyVersion;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyGroupRepository;
import com.ssafy.leaveservice.domain.policy.repository.LeavePolicyVersionRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
public class LeavePolicyVersionService {

    private final LeavePolicyGroupRepository leavePolicyGroupRepository;
    private final LeavePolicyVersionRepository leavePolicyVersionRepository;
    private final EmployeeAccess employeeAccess;
    private final Validator validator;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deletePolicyVersion(Long policyId, Long versionId) {
        // 관리자 권한 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 그룹을 먼저 잠근 뒤 해당 그룹의 삭제 대상 버전을 잠금
        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findByIdForUpdate(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );
        LeavePolicyVersion policyVersion = leavePolicyVersionRepository
                .findByIdAndPolicyIdForDelete(versionId, policyId).orElseThrow(
                        () -> new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND)
                );

        // 활성 포인터와 상태에 관계없는 전체 신청·발생 이력 확인
        boolean isActive = Objects.equals(policyGroup.getActivePolicyVersionId(), policyVersion.getPolicyVersionId());
        if (isActive || leavePolicyVersionRepository.existsUsageHistory(versionId)) {
            throw new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_IN_USE);
        }

        // 규칙을 순서대로 잠근 뒤 잠금 대기 중 커밋된 참조를 다시 확인
        leavePolicyVersionRepository.lockAccrualRules(versionId);
        leavePolicyVersionRepository.lockTypeRules(versionId);
        if (leavePolicyVersionRepository.existsUsageHistory(versionId)) {
            throw new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_IN_USE);
        }

        // 하위 설정부터 삭제하고 FK 오류도 같은 트랜잭션에서 확인
        leavePolicyVersionRepository.deleteTypeRules(versionId);
        leavePolicyVersionRepository.deleteAccrualRules(versionId);
        leavePolicyVersionRepository.delete(policyVersion);
        leavePolicyVersionRepository.flush();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LeavePolicyVersionDetailResponse updatePolicyVersion(
            Long policyId,
            Long versionId,
            LeavePolicyVersionUpdateRequest request
    ) {
        // 관리자 권한 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 생성 API와 동일하게 그룹을 먼저 잠근 뒤 소속 버전을 잠금
        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findByIdForUpdate(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );
        LeavePolicyVersion policyVersion = leavePolicyVersionRepository
                .findByIdAndPolicyIdForUpdate(versionId, policyId).orElseThrow(
                        () -> new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND)
                );

        // Entity를 변경하기 전에 생략한 필드를 병합하고 최종 설정 검증
        if (!request.isUpdatePresent() || !request.isNonNullableFieldsValid()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        LeavePolicyVersionSettings updatedSettings = mergeSettings(policyVersion, request);
        if (!validator.validate(updatedSettings).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 실제 값이 바뀔 때만 활성 상태와 신청·발생 이력을 확인
        boolean isActive = Objects.equals(policyGroup.getActivePolicyVersionId(), policyVersion.getPolicyVersionId());
        boolean settingsChanged = hasSettingsChanged(policyVersion, updatedSettings);
        if (settingsChanged && (isActive || leavePolicyVersionRepository.existsUsageHistory(versionId))) {
            throw new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_IN_USE);
        }

        // 모든 검증이 끝난 뒤 한 번만 반영하고 JPA 변경 감지로 저장
        if (settingsChanged) {
            policyVersion.updateSettings(updatedSettings);
        }
        LeavePolicyVersionDetailResponse response = LeavePolicyVersionDetailResponse.from(
                policyVersion,
                policyGroup.getPolicyId(),
                isActive
        );
        return response;
    }

    private LeavePolicyVersionSettings mergeSettings(
            LeavePolicyVersion policyVersion,
            LeavePolicyVersionUpdateRequest request
    ) {
        PolicyBaseType baseType = request.hasField("baseType") ? request.baseType() : policyVersion.getBaseType();
        String fiscalStart = request.hasField("fiscalStart") ? request.fiscalStart() : policyVersion.getFiscalStart();
        Integer unitMinutes = request.hasField("unitMinutes") ? request.unitMinutes() : policyVersion.getUnitMinutes();
        Boolean carryOverAllowed = request.hasField("carryOverAllowed")
                ? request.carryOverAllowed() : policyVersion.isCarryOverAllowed();
        Integer carryOverLimitMinutes = request.hasField("carryOverLimitMinutes")
                ? request.carryOverLimitMinutes() : policyVersion.getCarryOverLimitMinutes();
        String expiryRule = request.hasField("expiryRule") ? request.expiryRule() : policyVersion.getExpiryRule();
        OffsetDateTime effectiveFrom = request.hasField("effectiveFrom")
                ? request.effectiveFrom() : policyVersion.getEffectiveFrom();
        OffsetDateTime effectiveTo = request.hasField("effectiveTo")
                ? request.effectiveTo() : policyVersion.getEffectiveTo();

        return new LeavePolicyVersionSettings(
                baseType,
                fiscalStart,
                unitMinutes,
                carryOverAllowed,
                carryOverLimitMinutes,
                expiryRule,
                effectiveFrom,
                effectiveTo
        );
    }

    private boolean hasSettingsChanged(LeavePolicyVersion policyVersion, LeavePolicyVersionSettings settings) {
        return policyVersion.getBaseType() != settings.baseType()
                || !Objects.equals(policyVersion.getFiscalStart(), settings.fiscalStart())
                || policyVersion.getUnitMinutes() != settings.unitMinutes()
                || policyVersion.isCarryOverAllowed() != settings.carryOverAllowed()
                || !Objects.equals(policyVersion.getCarryOverLimitMinutes(), settings.carryOverLimitMinutes())
                || !Objects.equals(policyVersion.getExpiryRule(), settings.expiryRule())
                || !isSameInstant(policyVersion.getEffectiveFrom(), settings.effectiveFrom())
                || !isSameInstant(policyVersion.getEffectiveTo(), settings.effectiveTo());
    }

    private boolean isSameInstant(OffsetDateTime first, OffsetDateTime second) {
        if (first == null || second == null) {
            return first == null && second == null;
        }
        return first.isEqual(second);
    }

    @Transactional
    public LeavePolicyVersionDetailResponse createPolicyVersion(
            Long policyId,
            LeavePolicyVersionCreateRequest request
    ) {
        // 관리자 권한 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 그룹 행을 잠가 같은 그룹의 버전 번호 계산과 저장을 직렬화
        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findByIdForUpdate(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );

        // 현재 최대 버전 번호를 기준으로 다음 번호 계산
        int maxVersionNo = leavePolicyVersionRepository.findMaxVersionNoByPolicyId(policyId);
        if (maxVersionNo == Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
        int nextVersionNo = maxVersionNo + 1;

        // 활성 포인터와 하위 규칙은 변경하지 않고 새 버전만 저장
        LeavePolicyVersion policyVersion = LeavePolicyVersion.builder()
                .policyGroup(policyGroup)
                .versionNo(nextVersionNo)
                .baseType(request.baseType())
                .fiscalStart(request.fiscalStart())
                .unitMinutes(request.unitMinutes())
                .carryOverAllowed(request.carryOverAllowed())
                .carryOverLimitMinutes(request.carryOverLimitMinutes())
                .expiryRule(request.expiryRule())
                .effectiveFrom(request.effectiveFrom())
                .effectiveTo(request.effectiveTo())
                .build();
        LeavePolicyVersion savedPolicyVersion = leavePolicyVersionRepository.save(policyVersion);

        // DB에서 생성한 ID와 생성 시각을 포함하여 응답 생성
        LeavePolicyVersionDetailResponse response = LeavePolicyVersionDetailResponse.from(
                savedPolicyVersion,
                policyGroup.getPolicyId(),
                false
        );
        return response;
    }

    @Transactional(readOnly = true)
    public LeavePolicyVersionDetailResponse getPolicyVersion(Long policyId, Long versionId) {
        // 관리자 권한 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 정책 그룹 조회
        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findById(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );

        // 요청한 그룹에 속한 정책 버전 조회
        LeavePolicyVersion policyVersion = leavePolicyVersionRepository
                .findByPolicyVersionIdAndPolicyGroupPolicyId(versionId, policyId).orElseThrow(
                        () -> new BusinessException(ErrorCode.LEAVE_POLICY_VERSION_NOT_FOUND)
                );

        // 활성 포인터를 기준으로 상세 응답 생성
        Long activePolicyVersionId = policyGroup.getActivePolicyVersionId();
        boolean isActive = activePolicyVersionId != null
                && activePolicyVersionId.equals(policyVersion.getPolicyVersionId());
        LeavePolicyVersionDetailResponse response = LeavePolicyVersionDetailResponse.from(
                policyVersion,
                policyGroup.getPolicyId(),
                isActive
        );
        return response;
    }

    @Transactional(readOnly = true)
    public List<LeavePolicyVersionListResponse> getPolicyVersions(Long policyId) {
        // 관리자 권한 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 정책 그룹 조회
        LeavePolicyGroup policyGroup = leavePolicyGroupRepository.findById(policyId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_POLICY_GROUP_NOT_FOUND)
        );

        // 그룹의 정책 버전을 번호 오름차순으로 조회
        List<LeavePolicyVersion> policyVersions =
                leavePolicyVersionRepository.findAllByPolicyGroupPolicyIdOrderByVersionNoAsc(policyId);

        // 활성 포인터와 비교하여 목록 응답 생성
        Long activePolicyVersionId = policyGroup.getActivePolicyVersionId();
        List<LeavePolicyVersionListResponse> responses = new ArrayList<>();
        for (LeavePolicyVersion policyVersion : policyVersions) {
            boolean isActive = activePolicyVersionId != null
                    && activePolicyVersionId.equals(policyVersion.getPolicyVersionId());
            LeavePolicyVersionListResponse response = LeavePolicyVersionListResponse.from(policyVersion, isActive);
            responses.add(response);
        }
        return responses;
    }
}
