package com.ssafy.leaveservice.client.hr;

import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;

/** 실제 계약을 연결하기 전에는 URI 설정만으로도 조회가 활성화되지 않는다. */
public final class UnavailableHrClient implements HrClient {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public EmployeeInfo findEmployee(Long employeeId) {
        // TODO: HR 직원 조회 URI·외부 DTO·Bearer 전달 계약 확정 후 연결한다.
        throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
    }

    @Override
    public MemberBatch findDepartmentMembers(Long departmentId) {
        // TODO: User Service deptId 기반 부서원 조회를 활성화할 때 설정과 인증을 확인한다.
        throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
    }

    @Override
    public DutyInfo findDuty(Long dutyId) {
        // TODO: HR 직책 조회 URI·실제 직책명 필드·인증 계약 확정 후 연결한다.
        throw new BusinessException(ErrorCode.HR_VERIFICATION_UNAVAILABLE);
    }
}
