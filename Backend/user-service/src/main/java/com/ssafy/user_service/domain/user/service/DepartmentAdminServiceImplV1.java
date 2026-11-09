package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.DepartmentResponse;
import com.ssafy.user_service.domain.user.entity.Department;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.enums.DeptType;
import com.ssafy.user_service.domain.user.entity.enums.EmploymentStatus;
import com.ssafy.user_service.domain.user.repository.DepartmentRepository;
import com.ssafy.user_service.domain.user.repository.EmployeeRepository;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DepartmentAdminServiceImplV1 implements DepartmentAdminService {

    /** 부서 계층 최대 깊이. 데이터가 잘못돼 상위 부서가 순환해도 끝나도록 (조직 체인 조회와 같은 값) */
    private static final int MAX_DEPTH = 20;

    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;

    @Override
    public List<DepartmentResponse> findAll() {
        return departmentRepository.findAll(Sort.by("deptId")).stream()
                .map(DepartmentResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public DepartmentResponse assignHead(Long deptId, Long empId) {
        Department department = getDepartment(deptId);
        Employee employee = employeeRepository.findById(empId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND));
        if (employee.getStatus() != EmploymentStatus.EMPLOYED) {
            throw new BusinessException(ErrorCode.DEPT_HEAD_NOT_EMPLOYED);
        }
        if (!isInSubtree(employee.getJob().getDeptId(), deptId)) {
            throw new BusinessException(ErrorCode.DEPT_HEAD_NOT_MEMBER);
        }
        department.assignHead(empId);
        return DepartmentResponse.from(department);
    }

    @Override
    @Transactional
    public DepartmentResponse clearHead(Long deptId) {
        Department department = getDepartment(deptId);
        department.clearHead();
        return DepartmentResponse.from(department);
    }

    @Override
    @Transactional
    public DepartmentResponse changeType(Long deptId, DeptType deptType) {
        Department department = getDepartment(deptId);
        Department parent = department.getParent();

        // 최상위 부서만 회사. 회사 아래에 또 회사를 두거나, 최상위를 본부·팀으로 두지 않는다
        if ((parent == null) != (deptType == DeptType.COMPANY)) {
            throw new BusinessException(ErrorCode.DEPT_TYPE_HIERARCHY);
        }
        // 상위 부서보다 높은 단계일 수 없다 (같은 단계는 허용: 팀 아래 팀)
        if (parent != null && parent.getDeptType() != null && isHigher(deptType, parent.getDeptType())) {
            throw new BusinessException(ErrorCode.DEPT_TYPE_HIERARCHY);
        }
        // 바꾼 뒤에도 하위 부서가 이 부서보다 높은 단계면 안 된다
        boolean childHigher = departmentRepository.findByParent_DeptId(deptId).stream()
                .map(Department::getDeptType)
                .filter(Objects::nonNull)
                .anyMatch(childType -> isHigher(childType, deptType));
        if (childHigher) {
            throw new BusinessException(ErrorCode.DEPT_TYPE_HIERARCHY);
        }

        department.changeType(deptType);
        return DepartmentResponse.from(department);
    }

    private Department getDepartment(Long deptId) {
        return departmentRepository.findById(deptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPT_NOT_FOUND));
    }

    /** memberDeptId 가 rootDeptId 이거나 그 하위 부서인지. 소속 부서부터 상위로 올라가며 찾는다 */
    private boolean isInSubtree(Long memberDeptId, Long rootDeptId) {
        if (memberDeptId == null) {
            return false;
        }
        Department dept = departmentRepository.findById(memberDeptId).orElse(null);
        Set<Long> visited = new HashSet<>();
        while (dept != null && visited.size() < MAX_DEPTH && visited.add(dept.getDeptId())) {
            if (dept.getDeptId().equals(rootDeptId)) {
                return true;
            }
            dept = dept.getParent();
        }
        return false;
    }

    /** a 가 b 보다 높은 단계인지 (COMPANY > DIVISION > TEAM, enum 선언 순서) */
    private static boolean isHigher(DeptType a, DeptType b) {
        return a.ordinal() < b.ordinal();
    }
}
