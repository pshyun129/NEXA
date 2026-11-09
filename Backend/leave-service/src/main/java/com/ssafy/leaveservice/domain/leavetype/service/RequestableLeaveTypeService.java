package com.ssafy.leaveservice.domain.leavetype.service;

import com.ssafy.leaveservice.domain.leavetype.dto.RequestableLeaveTypeResponse;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.leavetype.repository.projection.RequestableLeaveTypeSettings;
import com.ssafy.leaveservice.domain.policy.dto.MyLeavePolicyResponse;
import com.ssafy.leaveservice.domain.policy.service.MyLeavePolicyService;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RequestableLeaveTypeService {

    private final MyLeavePolicyService myLeavePolicyService;
    private final LeaveTypeRepository leaveTypeRepository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RequestableLeaveTypeResponse> getRequestableTypes() {
        // 기존 직원 식별과 현재 적용 정책 판정을 한 번만 수행
        MyLeavePolicyResponse policy = myLeavePolicyService.getMyPolicy();

        return findRequestableTypes(policy.policyVersionId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RequestableLeaveTypeResponse> getRequestableTypesAt(OffsetDateTime asOf) {
        // Preview의 기준 시각으로 기존 정책 및 유형 검증 재사용
        MyLeavePolicyResponse policy = myLeavePolicyService.getMyPolicyAt(asOf);
        return findRequestableTypes(policy.policyVersionId());
    }

    private List<RequestableLeaveTypeResponse> findRequestableTypes(Long policyVersionId) {
        // 같은 스냅샷에서 활성 마스터와 신청 가능한 규칙을 ID 순으로 조회
        List<RequestableLeaveTypeSettings> settings = leaveTypeRepository
                .findRequestableSettingsByVersionId(policyVersionId);

        // 단위·시간 정합성 검증 후 실제 적용 제한으로 응답 생성
        List<RequestableLeaveTypeResponse> response = new ArrayList<>();
        for (RequestableLeaveTypeSettings typeSettings : settings) {
            validateSettings(typeSettings);

            Integer effectiveMinMinutes = typeSettings.policyMinMinutes();
            Integer effectiveMaxMinutes = typeSettings.masterMaxMinutes();
            Integer policyMaxMinutes = typeSettings.policyMaxMinutes();
            if (policyMaxMinutes != null) {
                effectiveMaxMinutes = effectiveMaxMinutes == null
                        ? policyMaxMinutes : Math.min(effectiveMaxMinutes, policyMaxMinutes);
            }
            RequestableLeaveTypeResponse leaveType = new RequestableLeaveTypeResponse(
                    typeSettings.leaveTypeId(),
                    typeSettings.leaveTypeName(),
                    typeSettings.allowedUnits(),
                    effectiveMinMinutes,
                    effectiveMaxMinutes
            );
            response.add(leaveType);
        }
        return response;
    }

    private void validateSettings(RequestableLeaveTypeSettings settings) {
        Integer minMinutes = settings.policyMinMinutes();
        Integer maxMinutes = settings.policyMaxMinutes();
        Integer masterMaxMinutes = settings.masterMaxMinutes();
        boolean invalidRange = (minMinutes != null && minMinutes <= 0)
                || (maxMinutes != null && maxMinutes <= 0)
                || (minMinutes != null && maxMinutes != null && minMinutes > maxMinutes);
        boolean exceedsMasterLimit = masterMaxMinutes != null
                && (masterMaxMinutes <= 0 || (minMinutes != null && minMinutes > masterMaxMinutes)
                    || (maxMinutes != null && maxMinutes > masterMaxMinutes));
        if (settings.allowedUnits() == null || settings.requestUnit() == null
                || !settings.allowedUnits().isCompatibleWith(settings.requestUnit())
                || invalidRange || exceedsMasterLimit) {
            throw new BusinessException(ErrorCode.LEAVE_POLICY_TYPE_RULE_CONFLICT);
        }
    }
}
