package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.OrgChainResponse;
import com.ssafy.user_service.domain.user.dto.OrgChainResponse.OrgUnit;
import com.ssafy.user_service.domain.user.entity.Department;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.enums.EmploymentStatus;
import com.ssafy.user_service.domain.user.repository.DepartmentRepository;
import com.ssafy.user_service.domain.user.repository.EmployeeRepository;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganizationServiceImplV1 implements OrganizationService {

    /** 부서 계층 최대 깊이. 데이터가 잘못돼 상위 부서가 순환해도 끝나도록 */
    private static final int MAX_DEPTH = 20;

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;

    @Override
    public OrgChainResponse getOrgChain(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND));
        Long deptId = employee.getJob().getDeptId();
        Department dept = departmentRepository.findById(deptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND));

        // 소속 부서부터 최상위까지. 이미 지나온 부서가 다시 나오면(순환) 멈춘다
        List<Department> path = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        while (dept != null && path.size() < MAX_DEPTH && visited.add(dept.getDeptId())) {
            path.add(dept);
            dept = dept.getParent();
        }

        // 부서장을 한 번에 조회해 재직 중인 사람만 남긴다 (휴직·퇴직한 부서장은 공석으로 본다)
        Set<Long> headIds = path.stream()
                .map(Department::getHeadEmpId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> activeHeads = employeeRepository.findAllById(headIds).stream()
                .filter(head -> head.getStatus() == EmploymentStatus.EMPLOYED)
                .map(Employee::getEmpId)
                .collect(Collectors.toSet());

        List<OrgUnit> chain = path.stream()
                .map(d -> new OrgUnit(d.getDeptId(), d.getName(), d.getDeptType(),
                        activeHeads.contains(d.getHeadEmpId()) ? d.getHeadEmpId() : null))
                .toList();
        return new OrgChainResponse(employeeId, deptId, chain);
    }
}
