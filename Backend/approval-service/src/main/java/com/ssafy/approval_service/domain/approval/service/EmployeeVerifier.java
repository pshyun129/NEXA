package com.ssafy.approval_service.domain.approval.service;

import com.ssafy.approval_service.global.client.UserServiceClient;
import com.ssafy.approval_service.global.client.UserServiceClient.EmployeeStatus;
import com.ssafy.approval_service.global.exception.BusinessException;
import com.ssafy.approval_service.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 결재선에 넣을 사원이 실제로 있고 재직 중인지 user-service 에 확인한다.
 * 역할(결재자/합의자/참조자/열람자)과 관계없이 재직(EMPLOYED)만 허용하고,
 * 확인할 수 없으면(user-service 장애) 저장하지 않도록 503으로 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeVerifier {

    private static final String EMPLOYED = "EMPLOYED";

    private final UserServiceClient userServiceClient;

    /** 사원 목록을 한 번의 호출로 확인. 없는 사원은 400 APPROVAL_015, 재직 중이 아니면 400 APPROVAL_016 */
    public void verifyActive(Collection<Long> empIds) {
        List<Long> distinctIds = empIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return;
        }

        Map<Long, String> statusById = fetch(distinctIds).stream()
                .collect(Collectors.toMap(EmployeeStatus::empId, EmployeeStatus::status, (a, b) -> a));
        for (Long empId : distinctIds) {
            String status = statusById.get(empId);
            if (status == null) {
                throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "존재하지 않는 사원: " + empId);
            }
            if (!EMPLOYED.equals(status)) {
                throw new BusinessException(ErrorCode.EMPLOYEE_NOT_ACTIVE, "재직 중이 아닌 사원: " + empId + " (" + status + ")");
            }
        }
    }

    /** 단건 확인 (결재자/참조자/열람자 추가) */
    public void verifyActive(Long empId) {
        verifyActive(List.of(empId));
    }

    private List<EmployeeStatus> fetch(List<Long> empIds) {
        try {
            UserServiceClient.EmployeeListResult result = userServiceClient.getEmployees(empIds);
            if (result == null || result.data() == null || result.data().employees() == null) {
                throw new IllegalStateException("user-service 응답에 사원 목록이 없음");
            }
            return result.data().employees();
        } catch (RestClientException | IllegalStateException e) {
            // 연결 실패, 타임아웃, 4xx/5xx, Eureka에 인스턴스 없음 등
            log.warn("user-service 사원 조회 실패 (ids={}): {}", empIds, e.getMessage());
            throw new BusinessException(ErrorCode.EMPLOYEE_SERVICE_UNAVAILABLE);
        }
    }
}
