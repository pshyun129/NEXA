package com.ssafy.leaveservice.domain.department.service;

import com.ssafy.leaveservice.domain.department.dto.DepartmentLeaveBalancesResponse;
import com.ssafy.leaveservice.domain.department.dto.DepartmentLeaveScheduleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class DepartmentLeaveService {
    private final DepartmentLeaveAccessService accessService;
    private final DepartmentLeaveQueryService queryService;
    private final Clock leavePreviewClock;

    public List<DepartmentLeaveScheduleResponse> getRequests(Long departmentId) {
        // HR 검증은 DB 트랜잭션 밖에서 완료
        List<Long> employeeIds = accessService.getScheduleEmployeeIds(departmentId);

        // 검증된 직원만 읽기 전용 일괄 조회
        return queryService.findRequests(employeeIds);
    }

    public DepartmentLeaveBalancesResponse getBalances(Long departmentId) {
        // HR 검증 후 한 번 확정한 시각을 응답과 SQL에서 공유
        List<Long> employeeIds = accessService.getAuthorizedEmployeeIds(departmentId);
        OffsetDateTime asOf = OffsetDateTime.now(leavePreviewClock).truncatedTo(ChronoUnit.MICROS);

        // 직원별로 분리된 현재 잔액 반환
        return queryService.findBalances(departmentId, employeeIds, asOf);
    }
}
