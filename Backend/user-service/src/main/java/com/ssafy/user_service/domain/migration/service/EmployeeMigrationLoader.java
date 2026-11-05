package com.ssafy.user_service.domain.migration.service;

import com.ssafy.user_service.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertResponse;
import com.ssafy.user_service.domain.migration.repository.MigrationBatchRowRepository;
import com.ssafy.user_service.domain.migration.repository.MigrationBatchRowRepository.Target;
import com.ssafy.user_service.domain.user.entity.Department;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.JobAssignment;
import com.ssafy.user_service.domain.user.entity.PersonalInfo;
import com.ssafy.user_service.domain.user.entity.enums.EmpType;
import com.ssafy.user_service.domain.user.entity.enums.EmploymentStatus;
import com.ssafy.user_service.domain.user.entity.enums.Gender;
import com.ssafy.user_service.domain.user.entity.enums.HireType;
import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;
import com.ssafy.user_service.domain.user.repository.DepartmentRepository;
import com.ssafy.user_service.domain.user.repository.DutyRepository;
import com.ssafy.user_service.domain.user.repository.EmployeeRepository;
import com.ssafy.user_service.domain.user.repository.GradeRepository;
import com.ssafy.user_service.domain.user.repository.JobCodeRepository;
import com.ssafy.user_service.domain.user.repository.PositionRepository;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import com.ssafy.user_service.global.security.Role;
import com.ssafy.user_service.global.security.TokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 사원 이관 upsert. 기준 키는 사번(emp_no).
 * - 부서·직위·직급·직책·직군·직종·직무는 ID 가 아니라 이름(부서는 코드 우선)으로 와서 여기서 ID 로 바꾼다.
 * - 수정 시 값이 없는 항목은 기존 값을 유지한다.
 * - 새 계정의 비밀번호는 아무도 모르는 임의값이다 (재설정 기능이 생기기 전까지 로그인 불가).
 * - 이 묶음의 사원을 기다리던 부서장 연결(pending_head_emp_no)을 끝에 처리한다.
 */
@Component
@RequiredArgsConstructor
class EmployeeMigrationLoader {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("empNo", "사번"), Map.entry("name", "이름"), Map.entry("engName", "영문이름"),
            Map.entry("email", "이메일"), Map.entry("dept", "부서"), Map.entry("position", "직위"),
            Map.entry("grade", "직급"), Map.entry("duty", "직책"), Map.entry("jobFamily", "직군"),
            Map.entry("jobKind", "직종"), Map.entry("jobRole", "직무"), Map.entry("empType", "직원구분"),
            Map.entry("hireType", "채용구분"), Map.entry("hireDate", "입사일"), Map.entry("recogHireDate", "인정입사일"),
            Map.entry("birthDate", "생년월일"), Map.entry("gender", "성별"), Map.entry("mobile", "휴대전화"),
            Map.entry("officePhone", "직통번호"), Map.entry("nationality", "국적"), Map.entry("address", "주소"),
            Map.entry("status", "재직상태"), Map.entry("resignDate", "퇴사일"));
    private static final int MAX_LISTED_PENDING = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final PositionRepository positionRepository;
    private final DutyRepository dutyRepository;
    private final GradeRepository gradeRepository;
    private final JobCodeRepository jobCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    private final MigrationBatchRowRepository batchRowRepository;

    MigrationUpsertResponse upsert(String batchId, List<MigrationUpsertRequest.Row> rows) {
        rejectIfParentsPending();
        MigrationReferenceLookup refs = new MigrationReferenceLookup(positionRepository.findAll(), dutyRepository.findAll(),
                gradeRepository.findAll(), jobCodeRepository.findAll(), departmentRepository.findAll());

        List<MigrationRow> parsed = rows.stream().map(r -> new MigrationRow(r, LABELS)).toList();
        List<String> empNos = parsed.stream().map(r -> r.string("empNo")).filter(Objects::nonNull).toList();
        List<String> emails = parsed.stream().map(r -> r.string("email")).filter(Objects::nonNull).toList();
        Map<String, Employee> byEmpNo = employeeRepository.findByEmpNoIn(empNos).stream()
                .collect(Collectors.toMap(Employee::getEmpNo, Function.identity()));
        Map<String, Employee> byLoginId = employeeRepository.findByLoginIdIn(emails).stream()
                .collect(Collectors.toMap(Employee::getLoginId, Function.identity()));

        // 한 요청에 BCrypt 를 한 번만 돌린다 (500행 × 행마다 해시면 migration 응답 타임아웃 30초를 넘긴다).
        // 평문은 버리므로 해시가 같아도 아무도 로그인할 수 없다
        String lockedPasswordHash = passwordEncoder.encode(randomSecret());

        int created = 0;
        int updated = 0;
        List<Long> newlyTerminated = new ArrayList<>();
        for (MigrationRow row : parsed) {
            String empNo = row.requiredString("empNo");
            Employee emp = byEmpNo.get(empNo);
            boolean isNew = emp == null;

            String email = row.string("email");
            Employee emailOwner = email == null ? null : byLoginId.get(email);
            if (emailOwner != null && emailOwner != emp) {
                throw row.error("이메일 '" + email + "'은(는) 이미 다른 사원(사번 " + emailOwner.getEmpNo() + ")이 쓰고 있습니다");
            }

            if (isNew) {
                emp = Employee.builder().empNo(empNo).passwordEnc(lockedPasswordHash).role(Role.USER).build();
            } else {
                batchRowRepository.recordUpdateIfAbsent(batchId, Target.EMPLOYEE, emp.getEmpId(), row.rowNo());
            }
            boolean wasTerminated = !isNew && emp.isTerminated();
            String oldEmail = emp.getLoginId();
            apply(emp, row, refs, isNew);

            if (isNew) {
                employeeRepository.save(emp);
                batchRowRepository.recordCreated(batchId, Target.EMPLOYEE, emp.getEmpId(), row.rowNo());
                byEmpNo.put(empNo, emp);
                created++;
            } else {
                if (!wasTerminated && emp.isTerminated()) {
                    newlyTerminated.add(emp.getEmpId());
                }
                updated++;
            }
            if (oldEmail != null && !oldEmail.equals(emp.getLoginId())) {
                byLoginId.remove(oldEmail);
            }
            byLoginId.put(emp.getLoginId(), emp);
        }

        linkPendingHeads(batchId, byEmpNo);
        deleteSessionsAfterCommit(newlyTerminated);
        return new MigrationUpsertResponse(created, updated);
    }

    /** 새 사원은 필수 항목이 모두 있어야 하고, 기존 사원은 주어진 항목만 바꾼다 */
    private void apply(Employee emp, MigrationRow row, MigrationReferenceLookup refs, boolean isNew) {
        JobAssignment oldJob = emp.getJob();
        PersonalInfo oldPersonal = emp.getPersonal();

        String name = pick(row, "name", isNew, emp.getName());
        String email = pick(row, "email", isNew, emp.getLoginId());

        Long deptId = resolve(row, "dept", isNew, oldJob == null ? null : oldJob.getDeptId(), refs::department);
        Long positionId = resolve(row, "position", isNew, oldJob == null ? null : oldJob.getPositionId(), refs::position);
        Long gradeId = resolve(row, "grade", isNew, oldJob == null ? null : oldJob.getGradeId(), refs::grade);
        Long dutyId = resolve(row, "duty", isNew, oldJob == null ? null : oldJob.getDutyId(), refs::duty);
        // 직군 → 직종 → 직무 순으로 상위 범위 안에서 찾는다. 주어지지 않은 단계는 기존 값을 범위로 쓴다
        Long familyId = resolve(row, "jobFamily", isNew, oldJob == null ? null : oldJob.getJobFamilyId(),
                (r, k) -> refs.jobCode(r, k, JobCodeType.FAMILY, null));
        Long kindId = resolve(row, "jobKind", isNew || row.has("jobFamily"), oldJob == null ? null : oldJob.getJobKindId(),
                (r, k) -> refs.jobCode(r, k, JobCodeType.KIND, familyId));
        Long roleId = resolve(row, "jobRole", isNew || row.has("jobKind"), oldJob == null ? null : oldJob.getJobRoleId(),
                (r, k) -> refs.jobCode(r, k, JobCodeType.ROLE, kindId));

        JobAssignment job = JobAssignment.builder()
                .deptId(deptId).positionId(positionId).gradeId(gradeId).dutyId(dutyId)
                .jobFamilyId(familyId).jobKindId(kindId).jobRoleId(roleId)
                .lastAppointDate(oldJob == null ? null : oldJob.getLastAppointDate())
                .build();

        PersonalInfo.PersonalInfoBuilder personal = PersonalInfo.builder();
        if (oldPersonal != null) {
            personal.engName(oldPersonal.getEngName()).officePhone(oldPersonal.getOfficePhone())
                    .mobile(oldPersonal.getMobile()).rrnEnc(oldPersonal.getRrnEnc())
                    .nationality(oldPersonal.getNationality()).stayStatus(oldPersonal.getStayStatus())
                    .gender(oldPersonal.getGender()).birthDate(oldPersonal.getBirthDate())
                    .address(oldPersonal.getAddress());
        }
        if (row.has("engName")) personal.engName(row.string("engName"));
        if (row.has("officePhone")) personal.officePhone(row.string("officePhone"));
        if (row.has("mobile")) personal.mobile(row.string("mobile"));
        if (row.has("nationality")) personal.nationality(row.string("nationality"));
        if (row.has("gender")) personal.gender(row.enumValue("gender", Gender.class));
        if (row.has("birthDate")) personal.birthDate(row.date("birthDate"));
        if (row.has("address")) personal.address(row.string("address"));

        EmploymentStatus status = row.has("status") ? row.enumValue("status", EmploymentStatus.class)
                : (emp.getStatus() == null ? EmploymentStatus.EMPLOYED : emp.getStatus());
        LocalDate resignDate = row.has("resignDate") ? row.date("resignDate") : emp.getResignDate();

        if (isNew && !row.has("hireDate")) {
            throw row.error("입사일 값이 없습니다");
        }
        emp.overwriteByMigration(email, name, job, personal.build(),
                row.has("empType") ? row.enumValue("empType", EmpType.class) : emp.getEmpType(),
                row.has("hireType") ? row.enumValue("hireType", HireType.class) : emp.getHireType(),
                row.has("hireDate") ? row.date("hireDate") : emp.getHireDate(),
                row.has("recogHireDate") ? row.date("recogHireDate") : emp.getRecogHireDate(),
                status, resignDate);
    }

    private static String pick(MigrationRow row, String key, boolean required, String current) {
        if (required) {
            return row.requiredString(key);
        }
        return row.has(key) ? row.string(key) : current;
    }

    private static Long resolve(MigrationRow row, String key, boolean required, Long current,
                                BiFunction<MigrationRow, String, Long> lookup) {
        if (row.has(key)) {
            return lookup.apply(row, key);
        }
        if (required || current == null) {
            // 새 사원이거나, 상위 코드(직군·직종)가 바뀌어 기존 하위 코드를 그대로 둘 수 없는 경우
            throw row.error(row.label(key) + " 값이 없습니다");
        }
        return current;
    }

    /** 조직 batch 가 끝났는데 상위 부서를 못 찾은 부서가 있으면, 사원을 싣기 전에 알린다 */
    private void rejectIfParentsPending() {
        List<Department> pending = departmentRepository.findByPendingParentCodeIsNotNull();
        if (pending.isEmpty()) {
            return;
        }
        String listed = pending.stream().limit(MAX_LISTED_PENDING)
                .map(d -> d.getDeptCode() + "(상위 " + d.getPendingParentCode() + ")")
                .collect(Collectors.joining(", "));
        throw new BusinessException(ErrorCode.MIGRATION_PENDING_PARENT,
                "상위 부서를 찾지 못한 부서가 " + pending.size() + "개 있습니다: " + listed
                        + (pending.size() > MAX_LISTED_PENDING ? " 외" : "")
                        + ". 조직 이관을 되돌리고 원본의 상위부서 코드를 확인하세요");
    }

    private void linkPendingHeads(String batchId, Map<String, Employee> byEmpNo) {
        if (byEmpNo.isEmpty()) {
            return;
        }
        for (Department dept : departmentRepository.findByPendingHeadEmpNoIn(byEmpNo.keySet())) {
            batchRowRepository.recordUpdateIfAbsent(batchId, Target.DEPARTMENT, dept.getDeptId(), null);
            dept.linkPendingHead(byEmpNo.get(dept.getPendingHeadEmpNo()).getEmpId());
        }
    }

    /** 퇴직으로 바뀐 사원의 refresh 세션 삭제. Redis 는 트랜잭션 밖이라 커밋된 뒤에만 지운다 */
    private void deleteSessionsAfterCommit(List<Long> empIds) {
        if (empIds.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                empIds.forEach(tokenStore::deleteRefreshToken);
            }
        });
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
