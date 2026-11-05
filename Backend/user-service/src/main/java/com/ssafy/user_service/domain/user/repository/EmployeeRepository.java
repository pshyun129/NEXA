package com.ssafy.user_service.domain.user.repository;

import com.ssafy.user_service.domain.user.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByLoginId(String loginId);
    boolean existsByLoginId(String loginId);
    List<Employee> findByJobDeptId(Long deptId);

    Optional<Employee> findByEmpNo(String empNo);

    /** 이관: 한 요청(최대 500행)의 사번을 한 번에 조회 */
    List<Employee> findByEmpNoIn(Collection<String> empNos);

    /** 이관: 한 요청의 이메일을 한 번에 조회 (다른 사번이 쓰는 이메일인지 확인) */
    List<Employee> findByLoginIdIn(Collection<String> loginIds);
}
