package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.EmployeeInfoResponse;
import com.ssafy.user_service.domain.user.entity.Department;
import com.ssafy.user_service.domain.user.entity.Duty;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.Grade;
import com.ssafy.user_service.domain.user.entity.JobAssignment;
import com.ssafy.user_service.domain.user.entity.Position;
import com.ssafy.user_service.domain.user.repository.DepartmentRepository;
import com.ssafy.user_service.domain.user.repository.DutyRepository;
import com.ssafy.user_service.domain.user.repository.GradeRepository;
import com.ssafy.user_service.domain.user.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 사원 응답에 소속 이름(부서·직위·직급·직책)을 붙인다.
 * 목록이어도 사원마다 조회하지 않고, 응답에 나온 ID 집합으로 종류별 IN 조회를 한 번씩만 한다 (최대 4쿼리).
 * 참조가 깨져 이름을 못 찾으면 그 이름만 null 로 둔다 (조회 API 가 참조 오류로 실패하지 않게).
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmployeeInfoAssembler {

    private final DepartmentRepository departmentRepository;
    private final PositionRepository positionRepository;
    private final GradeRepository gradeRepository;
    private final DutyRepository dutyRepository;

    public EmployeeInfoResponse toResponse(Employee employee) {
        return toResponses(List.of(employee)).getFirst();
    }

    public List<EmployeeInfoResponse> toResponses(List<Employee> employees) {
        if (employees.isEmpty()) {
            return List.of();
        }
        Map<Long, String> deptNames = names(employees, JobAssignment::getDeptId,
                departmentRepository, Department::getDeptId, Department::getName);
        Map<Long, String> positionNames = names(employees, JobAssignment::getPositionId,
                positionRepository, Position::getPositionId, Position::getName);
        Map<Long, String> gradeNames = names(employees, JobAssignment::getGradeId,
                gradeRepository, Grade::getGradeId, Grade::getName);
        Map<Long, String> dutyNames = names(employees, JobAssignment::getDutyId,
                dutyRepository, Duty::getDutyId, Duty::getName);

        return employees.stream()
                .map(employee -> {
                    JobAssignment job = employee.getJob();
                    return EmployeeInfoResponse.of(employee,
                            deptNames.get(job.getDeptId()),
                            positionNames.get(job.getPositionId()),
                            gradeNames.get(job.getGradeId()),
                            dutyNames.get(job.getDutyId()));
                })
                .toList();
    }

    /** 사원들이 가리키는 ID 를 모아 한 번에 조회하고 ID → 이름 Map 으로 만든다 */
    private static <T> Map<Long, String> names(List<Employee> employees, Function<JobAssignment, Long> idOfJob,
                                               JpaRepository<T, Long> repository,
                                               Function<T, Long> idOf, Function<T, String> nameOf) {
        Set<Long> ids = employees.stream()
                .map(employee -> idOfJob.apply(employee.getJob()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.findAllById(ids).stream()
                .filter(entity -> nameOf.apply(entity) != null)   // toMap 은 null 값을 받지 않는다
                .collect(Collectors.toMap(idOf, nameOf));
    }
}
