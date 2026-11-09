package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.OrgChainResponse;
import com.ssafy.user_service.domain.user.entity.Department;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.JobAssignment;
import com.ssafy.user_service.domain.user.entity.enums.DeptType;
import com.ssafy.user_service.domain.user.entity.enums.EmploymentStatus;
import com.ssafy.user_service.domain.user.repository.DepartmentRepository;
import com.ssafy.user_service.domain.user.repository.EmployeeRepository;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationServiceImplV1Test {

    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final OrganizationService service = new OrganizationServiceImplV1(employeeRepository, departmentRepository);

    private static Department dept(long id, DeptType type, Long headEmpId, Department parent) {
        Department dept = Department.builder().name("부서" + id).deptType(type).headEmpId(headEmpId).parent(parent).build();
        ReflectionTestUtils.setField(dept, "deptId", id);
        return dept;
    }

    private static Employee employee(long id, long deptId, EmploymentStatus status) {
        Employee employee = Employee.builder().name("사원" + id).job(JobAssignment.builder().deptId(deptId).build()).build();
        ReflectionTestUtils.setField(employee, "empId", id);
        ReflectionTestUtils.setField(employee, "status", status);
        return employee;
    }

    /** 부서장 조회(findAllById)는 넘어온 ID 중 등록된 사원만 돌려준다 */
    @SuppressWarnings("unchecked")
    private void givenEmployees(Employee... employees) {
        Map<Long, Employee> byId = new java.util.HashMap<>();
        for (Employee e : employees) {
            byId.put(e.getEmpId(), e);
            when(employeeRepository.findById(e.getEmpId())).thenReturn(Optional.of(e));
        }
        when(employeeRepository.findAllById(any())).thenAnswer(inv -> ((Collection<Long>) inv.getArgument(0)).stream()
                .map(byId::get).filter(java.util.Objects::nonNull).toList());
    }

    @Test
    void 소속_부서부터_최상위까지_부서장과_함께_돌려준다() {
        Department company = dept(1, DeptType.COMPANY, 100L, null);
        Department division = dept(2, DeptType.DIVISION, 200L, company);
        Department team = dept(3, DeptType.TEAM, 300L, division);
        when(departmentRepository.findById(3L)).thenReturn(Optional.of(team));
        givenEmployees(employee(1, 3, EmploymentStatus.EMPLOYED),
                employee(100, 1, EmploymentStatus.EMPLOYED),
                employee(200, 2, EmploymentStatus.EMPLOYED),
                employee(300, 3, EmploymentStatus.EMPLOYED));

        OrgChainResponse chain = service.getOrgChain(1L);

        assertThat(chain.deptId()).isEqualTo(3L);
        assertThat(chain.chain()).extracting(OrgChainResponse.OrgUnit::deptId).containsExactly(3L, 2L, 1L);
        assertThat(chain.chain()).extracting(OrgChainResponse.OrgUnit::headEmpId).containsExactly(300L, 200L, 100L);
        assertThat(chain.chain()).extracting(OrgChainResponse.OrgUnit::deptType)
                .containsExactly(DeptType.TEAM, DeptType.DIVISION, DeptType.COMPANY);
    }

    @Test
    void 휴직_퇴직한_부서장과_없는_부서장은_공석으로_본다() {
        Department company = dept(1, DeptType.COMPANY, 999L, null);              // 사원 테이블에 없음
        Department division = dept(2, DeptType.DIVISION, 200L, company);          // 퇴직
        Department team = dept(3, DeptType.TEAM, 300L, division);                 // 휴직
        when(departmentRepository.findById(3L)).thenReturn(Optional.of(team));
        givenEmployees(employee(1, 3, EmploymentStatus.EMPLOYED),
                employee(200, 2, EmploymentStatus.TERMINATED),
                employee(300, 3, EmploymentStatus.ON_LEAVE));

        OrgChainResponse chain = service.getOrgChain(1L);

        assertThat(chain.chain()).extracting(OrgChainResponse.OrgUnit::headEmpId).containsExactly(null, null, null);
    }

    @Test
    void 상위_부서가_순환해도_끝난다() {
        Department a = dept(1, DeptType.TEAM, null, null);
        Department b = dept(2, DeptType.DIVISION, null, a);
        ReflectionTestUtils.setField(a, "parent", b);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(a));
        givenEmployees(employee(1, 1, EmploymentStatus.EMPLOYED));

        OrgChainResponse chain = service.getOrgChain(1L);

        assertThat(chain.chain()).extracting(OrgChainResponse.OrgUnit::deptId).containsExactly(1L, 2L);
    }

    @Test
    void 없는_사원은_EMPLOYEE_NOT_FOUND() {
        when(employeeRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrgChain(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMPLOYEE_NOT_FOUND);
    }

    @Test
    void 소속_부서가_없으면_DEPARTMENT_NOT_FOUND() {
        givenEmployees(employee(1, 77, EmploymentStatus.EMPLOYED));
        when(departmentRepository.findById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrgChain(1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEPARTMENT_NOT_FOUND);
    }

    @Test
    void 부서장이_지정되지_않은_부서는_공석() {
        Department team = dept(3, DeptType.TEAM, null, null);
        when(departmentRepository.findById(3L)).thenReturn(Optional.of(team));
        givenEmployees(employee(1, 3, EmploymentStatus.EMPLOYED));

        assertThat(service.getOrgChain(1L).chain()).extracting(OrgChainResponse.OrgUnit::headEmpId)
                .containsExactly((Long) null);
    }
}
