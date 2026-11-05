package com.ssafy.user_service.domain.user.repository;

import com.ssafy.user_service.domain.user.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    /** 바로 아래 하위 부서들 */
    List<Department> findByParent_DeptId(Long parentDeptId);

    /** 이관: 상위 부서 연결을 기다리는 부서들 */
    List<Department> findByPendingParentCodeIsNotNull();

    /** 이관: 이 사번들을 부서장으로 기다리는 부서들 */
    List<Department> findByPendingHeadEmpNoIn(Collection<String> empNos);
}
