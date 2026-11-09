package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.domain.request.dto.AdminLeaveRequestListResponse;
import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestDetailResponse;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequest;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
public class AdminLeaveRequestService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeAccess employeeAccess;

    @Transactional(readOnly = true)
    public List<AdminLeaveRequestListResponse> getAllRequests() {
        // 관리자 권한 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 정책이나 신청 상태로 제외하지 않고 전체 직원의 신청 조회
        List<LeaveRequest> requests = leaveRequestRepository.findAllWithLeaveType();

        // 본인 목록과 동일한 업무 정보에 신청 소유 직원 ID를 포함
        List<AdminLeaveRequestListResponse> response = requests.stream()
                .map(AdminLeaveRequestListResponse::from)
                .toList();
        return response;
    }

    @Transactional(readOnly = true)
    public MyLeaveRequestDetailResponse getRequest(String requestIdValue) {
        // 관리자 권한 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 잘못된 숫자 형식도 관리자 검증 후 동일한 요청 오류로 처리
        Long requestId;
        try {
            requestId = Long.valueOf(requestIdValue);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (requestId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 관리자에게는 직원 소유권 제한 없이 신청과 날짜 상세 조회
        LeaveRequest leaveRequest = leaveRequestRepository.findDetailByRequestId(requestId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND)
        );

        // 본인 상세와 동일한 DTO로 저장된 정보 반환
        MyLeaveRequestDetailResponse response = MyLeaveRequestDetailResponse.from(leaveRequest);
        return response;
    }
}
