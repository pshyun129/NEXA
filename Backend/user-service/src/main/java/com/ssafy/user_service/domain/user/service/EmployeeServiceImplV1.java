package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.EmployeeCreateRequest;
import com.ssafy.user_service.domain.user.dto.EmployeeSummary;
import com.ssafy.user_service.domain.user.dto.EmployeeTerminateRequest;
import com.ssafy.user_service.domain.user.entity.Department;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.Grade;
import com.ssafy.user_service.domain.user.entity.JobAssignment;
import com.ssafy.user_service.domain.user.entity.PersonalInfo;
import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;
import com.ssafy.user_service.domain.user.repository.DepartmentRepository;
import com.ssafy.user_service.domain.user.repository.DutyRepository;
import com.ssafy.user_service.domain.user.repository.EmployeeRepository;
import com.ssafy.user_service.domain.user.repository.GradeRepository;
import com.ssafy.user_service.domain.user.repository.JobCodeRepository;
import com.ssafy.user_service.domain.user.repository.PositionRepository;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import com.ssafy.user_service.global.security.TokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmployeeServiceImplV1 implements EmployeeService {

    private static final Sort LIST_SORT = Sort.by("empId");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 일괄 조회 최대 개수. 결재선 인원보다 넉넉하게 두고, 전체 조회 우회는 막는다 */
    private static final int MAX_BULK_IDS = 100;

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final GradeRepository gradeRepository;
    private final PositionRepository positionRepository;
    private final DutyRepository dutyRepository;
    private final JobCodeRepository jobCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;

    @Override
    @Transactional
    public Long create(EmployeeCreateRequest r) {
        if (employeeRepository.existsByLoginId(r.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_LOGIN_ID);
        }
        validateReferences(r);

        Employee employee = Employee.builder()
                .empNo(r.empNo())
                .loginId(r.email())
                .passwordEnc(passwordEncoder.encode(r.password()))
                .name(r.name())
                .role(r.role())
                .job(JobAssignment.builder()
                        .deptId(r.deptId()).positionId(r.positionId()).gradeId(r.gradeId()).dutyId(r.dutyId())
                        .jobFamilyId(r.jobFamilyId()).jobKindId(r.jobKindId()).jobRoleId(r.jobRoleId())
                        .build())
                .personal(PersonalInfo.builder()
                        .engName(r.engName()).officePhone(r.officePhone()).mobile(r.mobile())
                        .nationality(r.nationality()).gender(r.gender()).birthDate(r.birthDate())
                        .address(r.address())
                        .build())
                .empType(r.empType())
                .hireType(r.hireType())
                .hireDate(r.hireDate())
                .recogHireDate(r.recogHireDate())
                .leavePolicyId(r.leavePolicyId())
                .workGroupId(r.workGroupId())
                .build();

        return employeeRepository.save(employee).getEmpId();
    }

    @Override
    @Transactional
    public void terminate(Long employeeId, EmployeeTerminateRequest request) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND));
        if (employee.isTerminated()) {
            throw new BusinessException(ErrorCode.EMPLOYEE_ALREADY_TERMINATED);
        }
        employee.terminate(request.resignDate(), request.resignReason());
        // 퇴직자는 재발급으로 세션을 이어갈 수 없어야 한다 (access는 최대 15분 뒤 자연 만료)
        tokenStore.deleteRefreshToken(employeeId);
    }

    @Override
    public Optional<Employee> findByLoginId(String loginId) {
        return employeeRepository.findByLoginId(loginId);
    }

    @Override
    public Optional<Employee> findById(Long employeeId) {
        return employeeRepository.findById(employeeId);
    }

    @Override
    public Page<Employee> findList(Long deptId, Integer page, Integer size) {
        Pageable pageable = pageRequest(page, size);
        if (deptId == null) {
            return employeeRepository.findAll(pageable);
        }
        if (!departmentRepository.existsById(deptId)) throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        return employeeRepository.findByJobDeptId(deptId, pageable);
    }

    /** 사원 ID 순 고정 (페이지 경계가 흔들리지 않게). page·size 가 둘 다 없으면 전체 */
    private static Pageable pageRequest(Integer page, Integer size) {
        if (page == null && size == null) {
            return Pageable.unpaged(LIST_SORT);
        }
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_PAGE_SIZE : size;
        if (p < 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "page는 0 이상이어야 합니다: " + p);
        }
        if (s < 1 || s > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "size는 1~" + MAX_PAGE_SIZE + " 이어야 합니다: " + s);
        }
        return PageRequest.of(p, s, LIST_SORT);
    }

    @Override
    public List<Employee> findAllByIds(Collection<Long> employeeIds) {
        if (employeeIds.size() > MAX_BULK_IDS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "ids는 최대 " + MAX_BULK_IDS + "개까지 조회할 수 있습니다.");
        }
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        return employeeRepository.findAllById(new HashSet<>(employeeIds));
    }


    @Override
    public EmployeeSummary summarize(Employee employee) {
        String deptName = departmentRepository.findById(employee.getJob().getDeptId())
                .map(Department::getName)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND));
        String gradeName = gradeRepository.findById(employee.getJob().getGradeId())
                .map(Grade::getName)
                .orElseThrow(() -> new BusinessException(ErrorCode.GRADE_NOT_FOUND));
        return new EmployeeSummary(employee.getEmpId(), employee.getName(), deptName, gradeName);
    }

    // employee의 NOT NULL FK 7개가 모두 실제 행을 가리키는지. DB FK 오류(500/409)가 아니라 400으로 응답하기 위함
    private void validateReferences(EmployeeCreateRequest r) {
        if (!departmentRepository.existsById(r.deptId())) throw new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND);
        if (!positionRepository.existsById(r.positionId())) throw new BusinessException(ErrorCode.POSITION_NOT_FOUND);
        if (!gradeRepository.existsById(r.gradeId())) throw new BusinessException(ErrorCode.GRADE_NOT_FOUND);
        if (!dutyRepository.existsById(r.dutyId())) throw new BusinessException(ErrorCode.DUTY_NOT_FOUND);
        if (!jobCodeRepository.existsByJobCodeIdAndType(r.jobFamilyId(), JobCodeType.FAMILY)
                || !jobCodeRepository.existsByJobCodeIdAndType(r.jobKindId(), JobCodeType.KIND)
                || !jobCodeRepository.existsByJobCodeIdAndType(r.jobRoleId(), JobCodeType.ROLE)) {
            throw new BusinessException(ErrorCode.JOB_CODE_NOT_FOUND);
        }
    }
}
