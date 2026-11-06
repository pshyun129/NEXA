package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * user-service의 이관용 내부 API 호출 (user-service InternalHrMigrationController).
 * references(기준정보) → orgs → employees 순으로 넣는다. references 는 이미 있는 값을 바꾸지 않는다 (updated = 이미 있던 수).
 * 그 밖에
 * GET  /internal/v1/hr/migration/references                → 지금 있는 직위·직급·직책·직무 코드
 * POST /internal/v1/hr/migration/employees/lookup {empNos} → 사번 → 사원 ID·명세서용 사원 정보 (급여 이관용)
 */
@Component
public class HrTargetLoader extends InternalApiLoader {

    private static final String PREFIX = "/internal/v1/hr/migration";
    private static final int LOOKUP_CHUNK = 1000;

    public HrTargetLoader(@Value("${migration.targets.hr.base-url}") String baseUrl) {
        super(baseUrl, PREFIX, Map.of(
                MigrationEntity.HR_CODE, "references",
                MigrationEntity.HR_ORG, "orgs",
                MigrationEntity.HR_EMPLOYEE, "employees"), "인사 서비스");
    }

    public ExistingReferences existingReferences() {
        return requireData(call(() -> restClient.get()
                .uri(PREFIX + "/references")
                .retrieve()
                .body(new ParameterizedTypeReference<InternalResult<ExistingReferences>>() {
                })));
    }

    /** 사번 → 사원. 대상에 없는 사번은 결과에 없다 */
    public Map<String, EmployeeRef> lookupEmployees(List<String> empNos) {
        List<EmployeeRef> found = new ArrayList<>();
        List<String> distinct = empNos.stream().distinct().toList();
        for (int from = 0; from < distinct.size(); from += LOOKUP_CHUNK) {
            List<String> chunk = distinct.subList(from, Math.min(from + LOOKUP_CHUNK, distinct.size()));
            found.addAll(requireData(call(() -> restClient.post()
                    .uri(PREFIX + "/employees/lookup")
                    .body(Map.of("empNos", chunk))
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalResult<List<EmployeeRef>>>() {
                    }))));
        }
        return found.stream().collect(Collectors.toMap(EmployeeRef::empNo, e -> e, (a, b) -> a));
    }

    public record EmployeeRef(String empNo, Long empId, String name, String deptName, String positionName, Long gradeId,
                              LocalDate hireDate) {
    }

    public record Named(String name) {
    }

    public record GradeRef(Long gradeId, String name, Integer level) {
    }

    public record JobCodeRef(Long jobCodeId, String type, String name, Long parentId) {
    }

    public record ExistingReferences(List<Named> positions, List<GradeRef> grades, List<Named> duties, List<JobCodeRef> jobCodes) {

        /** 직무 코드 → "직군 > 직종 > 직무" (ReferencePlan 의 값 표기와 같게) */
        public Set<String> jobPaths() {
            Map<Long, JobCodeRef> byId = jobCodes.stream().collect(Collectors.toMap(JobCodeRef::jobCodeId, c -> c));
            Set<String> out = new HashSet<>();
            for (JobCodeRef role : jobCodes) {
                JobCodeRef kind = role.parentId() == null ? null : byId.get(role.parentId());
                JobCodeRef family = kind == null || kind.parentId() == null ? null : byId.get(kind.parentId());
                if ("ROLE".equals(role.type()) && family != null) {
                    out.add(family.name() + " > " + kind.name() + " > " + role.name());
                }
            }
            return out;
        }
    }
}
