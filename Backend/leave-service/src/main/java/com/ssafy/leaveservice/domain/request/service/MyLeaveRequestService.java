package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.domain.request.dto.MyLeaveRequestListResponse;
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

@Service
@RequiredArgsConstructor
public class MyLeaveRequestService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeAccess employeeAccess;

    @Transactional(readOnly = true)
    public List<MyLeaveRequestListResponse> getMyRequests() {
        // 현재 로그인 직원 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 현재 정책이나 상태로 제외하지 않고 본인 신청 전체 조회
        List<LeaveRequest> requests = leaveRequestRepository.findAllWithLeaveTypeByEmployeeId(employeeId);

        // 신청에 저장된 상태와 수량을 그대로 응답 생성
        List<MyLeaveRequestListResponse> response = requests.stream()
                .map(MyLeaveRequestListResponse::from)
                .toList();
        return response;
    }

    @Transactional(readOnly = true)
    public MyLeaveRequestDetailResponse getMyRequest(Long requestId) {
        // 현재 로그인 직원 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 조회 대상 신청 ID 검증
        if (requestId == null || requestId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 신청 ID와 직원 ID를 함께 조회해 타인 신청과 미존재 신청을 동일하게 처리
        LeaveRequest leaveRequest = leaveRequestRepository.findDetailByRequestIdAndEmployeeId(requestId, employeeId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND)
        );

        // 저장된 신청 정보와 날짜별 상세를 현재 정책으로 재계산하지 않고 반환
        MyLeaveRequestDetailResponse response = MyLeaveRequestDetailResponse.from(leaveRequest);
        return response;
    }
}
