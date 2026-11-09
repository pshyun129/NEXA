package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.EmployeeCreateRequest;
import com.ssafy.user_service.domain.user.dto.EmployeeSummary;
import com.ssafy.user_service.domain.user.dto.EmployeeTerminateRequest;
import com.ssafy.user_service.domain.user.entity.Employee;

import org.springframework.data.domain.Page;

import java.util.Collection;
import org.springframework.data.domain.Page;

import java.util.List;
import org.springframework.data.domain.Page;

import java.util.Optional;

public interface EmployeeService {
    /** 사원 계정을 등록하고 생성된 employeeId를 반환한다. */
    Long create(EmployeeCreateRequest request);

    /** 퇴직 처리. 재적 상태를 TERMINATED로 바꾸고 이 사원의 refresh 세션을 삭제한다. */
    void terminate(Long employeeId, EmployeeTerminateRequest request);

    Optional<Employee> findByLoginId(String loginId);

    Optional<Employee> findById(Long employeeId);

    /** 부서명/직급명을 포함한 요약. 참조가 깨져 있으면 BusinessException */
    EmployeeSummary summarize(Employee employee);

    /**
     * 사원 목록 (사원 ID 순). deptId 가 있으면 그 부서원만(없는 부서면 BusinessException).
     * page·size 가 둘 다 null 이면 전체, 아니면 page(0부터, 기본 0)·size(1~100, 기본 20) 로 나눈다
     */
    Page<Employee> findList(Long deptId, Integer page, Integer size);

    /** 여러 사원을 한 번에 조회. 없는 ID는 결과에서 빠진다. 최대 개수를 넘으면 BusinessException */
    List<Employee> findAllByIds(Collection<Long> employeeIds);

}
