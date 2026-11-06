package com.ssafy.user_service.domain.user.repository;

import com.ssafy.user_service.domain.user.entity.Employee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByLoginId(String loginId);
    boolean existsByLoginId(String loginId);

    /** 부서원 목록. 정렬·페이지는 pageable 로 (unpaged 면 전체, 건수 쿼리 없음) */
    Page<Employee> findByJobDeptId(Long deptId, Pageable pageable);

    Optional<Employee> findByEmpNo(String empNo);

    /** 이관: 한 요청(최대 500행)의 사번을 한 번에 조회 */
    List<Employee> findByEmpNoIn(Collection<String> empNos);

    /** 이관: 한 요청의 이메일을 한 번에 조회 (다른 사번이 쓰는 이메일인지 확인) */
    List<Employee> findByLoginIdIn(Collection<String> loginIds);
}
