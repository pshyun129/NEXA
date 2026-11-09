package com.ssafy.user_service.domain.migration.service;

import com.ssafy.user_service.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertResponse;
import com.ssafy.user_service.domain.migration.repository.MigrationBatchRowRepository;
import com.ssafy.user_service.domain.migration.repository.MigrationBatchRowRepository.Target;
import com.ssafy.user_service.domain.user.entity.Company;
import com.ssafy.user_service.domain.user.entity.Department;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.enums.DeptType;
import com.ssafy.user_service.domain.user.repository.CompanyRepository;
import com.ssafy.user_service.domain.user.repository.DepartmentRepository;
import com.ssafy.user_service.domain.user.repository.EmployeeRepository;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 조직(부서) 이관 upsert. 기준 키는 부서코드(dept_code).
 * 상위 부서는 같은 파일의 뒤쪽(또는 다음 500행 묶음)에 나올 수 있어서, 행을 모두 저장한 뒤 연결하고
 * 그래도 없으면 pending_parent_code 에 남겨 다음 요청에서 다시 연결한다.
 * 부서장(headEmpNo)은 조직을 먼저 적재하므로 보통 아직 없다 → pending_head_emp_no 에 두고 사원 적재 때 연결한다.
 */
@Component
@RequiredArgsConstructor
class DepartmentMigrationLoader {

    private static final Map<String, String> LABELS = Map.of(
            "deptCode", "부서코드", "name", "부서명", "parentDeptCode", "상위부서",
            "sortOrder", "정렬순서", "deptType", "부서유형", "headEmpNo", "부서장 사번");
    private static final int MAX_DEPTH = 100;

    private final DepartmentRepository departmentRepository;
    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final MigrationBatchRowRepository batchRowRepository;

    MigrationUpsertResponse upsert(String batchId, List<MigrationUpsertRequest.Row> rows) {
        Company company = singleCompany();
        Map<String, Department> byCode = new HashMap<>();
        for (Department d : departmentRepository.findAll()) {
            if (d.getDeptCode() != null) {
                byCode.put(d.getDeptCode(), d);
            }
        }

        int created = 0;
        int updated = 0;
        List<ParentLink> links = new ArrayList<>();
        for (MigrationUpsertRequest.Row raw : rows) {
            MigrationRow row = new MigrationRow(raw, LABELS);
            String code = row.requiredString("deptCode");
            Integer sortOrder = row.integer("sortOrder");
            DeptType deptType = row.enumValue("deptType", DeptType.class);

            Department dept = byCode.get(code);
            if (dept == null) {
                dept = departmentRepository.save(Department.builder()
                        .deptCode(code).name(row.requiredString("name")).sortOrder(sortOrder)
                        .company(company).deptType(deptType)
                        .build());
                byCode.put(code, dept);
                batchRowRepository.recordCreated(batchId, Target.DEPARTMENT, dept.getDeptId(), row.rowNo());
                created++;
            } else {
                batchRowRepository.recordUpdateIfAbsent(batchId, Target.DEPARTMENT, dept.getDeptId(), row.rowNo());
                dept.updateBasics(row.string("name"), sortOrder);
                if (deptType != null) {
                    dept.changeType(deptType);
                }
                updated++;
            }

            String parentCode = row.string("parentDeptCode");
            if (parentCode != null) {
                if (parentCode.equals(code)) {
                    throw row.error("상위부서가 자기 자신입니다 (" + code + ")");
                }
                links.add(new ParentLink(row, dept, parentCode));
            }
            String headEmpNo = row.string("headEmpNo");
            if (headEmpNo != null) {
                Long headEmpId = employeeRepository.findByEmpNo(headEmpNo).map(Employee::getEmpId).orElse(null);
                if (headEmpId != null) {
                    dept.linkPendingHead(headEmpId);
                } else {
                    dept.deferHead(headEmpNo);
                }
            }
        }

        // 2단계: 이 묶음의 상위 부서 연결 (뒤쪽 행에서 만든 부서도 이제 byCode 에 있다)
        for (ParentLink link : links) {
            Department parent = byCode.get(link.parentCode());
            if (parent == null) {
                link.dept().deferParent(link.parentCode());
            } else {
                linkWithoutCycle(link.dept(), parent, link.row());
            }
        }

        // 앞선 묶음(또는 이전 batch)에서 상위 부서를 못 찾아 미뤄 둔 부서들
        for (Department waiting : departmentRepository.findByPendingParentCodeIsNotNull()) {
            Department parent = byCode.get(waiting.getPendingParentCode());
            if (parent != null) {
                batchRowRepository.recordUpdateIfAbsent(batchId, Target.DEPARTMENT, waiting.getDeptId(), null);
                linkWithoutCycle(waiting, parent, null);
            }
        }
        return new MigrationUpsertResponse(created, updated);
    }

    private void linkWithoutCycle(Department dept, Department parent, MigrationRow row) {
        int depth = 0;
        for (Department p = parent; p != null; p = p.getParent()) {
            if (p == dept || p.getDeptId().equals(dept.getDeptId()) || ++depth > MAX_DEPTH) {
                String msg = "상위부서 지정이 순환합니다 (" + dept.getDeptCode() + " → " + parent.getDeptCode() + ")";
                throw row != null ? row.error(msg) : new BusinessException(ErrorCode.MIGRATION_ROW_INVALID, msg);
            }
        }
        dept.linkParent(parent);
    }

    /** 부서는 회사에 붙어야 한다(NOT NULL). 단일 회사 전제이며 여러 개면 고르지 않고 실패한다 */
    private Company singleCompany() {
        List<Company> companies = companyRepository.findAll();
        if (companies.size() != 1) {
            throw new BusinessException(ErrorCode.MIGRATION_COMPANY_AMBIGUOUS,
                    "부서를 붙일 회사가 " + companies.size() + "개입니다 (1개여야 합니다)");
        }
        return companies.get(0);
    }

    private record ParentLink(MigrationRow row, Department dept, String parentCode) {
    }
}
