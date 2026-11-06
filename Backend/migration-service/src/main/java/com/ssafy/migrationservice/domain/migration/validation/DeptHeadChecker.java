package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.mapping.MappingService;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.repository.MigrationFileRepository;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchemas;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 조직 파일의 부서장 사번을 같은 작업의 직원명부와 대조 (user-service 부서장 지정 규칙과 같게).
 * - 직원명부에 없는 사번. 이름을 적었고 명부에 그 이름이 한 명뿐이면 사번으로 바꾸자고 제안
 * - 재직 중이 아님 (명부 재직상태가 휴직·퇴직이거나 퇴사일이 지남)
 * - 그 부서나 하위 부서 소속이 아님. 명부의 부서 값을 이 파일의 부서코드·부서명으로 찾고, 못 찾으면 보지 않는다
 * 부서장이 비어 있으면 결재선 자동 결정이 안 되므로(user-service V4 채우기는 한 번만 돈다) 넣는 쪽이 채워야 한다.
 * - V4와 같은 규칙으로 후보를 찾는다: 부서 유형에 맞는 직책(회사=대표이사, 본부=본부장, 팀=팀장)인 그 부서 재직자가 한 명이면 제안
 * - 부서장 열 자체가 없으면 파일 단위로 한 번 알린다
 * 직원명부를 나중에 올리면 MigrationService가 조직 파일도 다시 검증한다.
 */
@Component
@RequiredArgsConstructor
public class DeptHeadChecker {

    private static final int MAX_DEPTH = 20;
    // 부서 유형 → 부서장 직책 (user-service V4와 같게). 유형 값이 없거나 틀리면 V4처럼 트리 깊이로 정한다
    private static final Map<String, String> HEAD_DUTY = Map.of("COMPANY", "대표이사", "DIVISION", "본부장", "TEAM", "팀장");
    private static final List<String> TYPE_BY_DEPTH = List.of("COMPANY", "DIVISION", "TEAM");

    private final MigrationFileRepository fileRepository;
    private final StagingRowRepository stagingRowRepository;
    private final MappingService mappingService;

    /** 명부의 한 사람. 사번이 명부에 두 번 이상 있으면 어느 줄이 맞는지 몰라 소속·재직은 보지 않는다 */
    private record Member(String name, String dept, String duty, boolean employed, boolean duplicated) {
    }

    public List<ValidationIssue> check(MigrationFile file, List<ParsedTable.Row> rows) {
        if (file.getEntity() != MigrationEntity.HR_ORG) {
            return List.of();
        }
        Map<String, String> f2c = mappingService.fieldToColumn(file.getFileId());
        String headCol = f2c.get("headEmpNo");
        String codeCol = f2c.get("deptCode");
        if (codeCol == null) {
            return List.of();
        }
        Optional<Map<String, Member>> rosterOpt = roster(file.getMigrationId());
        if (rosterOpt.isEmpty()) {
            return List.of();
        }
        Map<String, Member> roster = rosterOpt.get();
        Map<String, List<String>> empNosByName = new HashMap<>();
        roster.forEach((emp, m) -> {
            if (m.name() != null) {
                empNosByName.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(emp);
            }
        });

        Map<String, String> codeByLabel = codeByLabel(rows, codeCol, f2c.get("name"));
        String parentCol = f2c.get("parentDeptCode");
        Map<String, String> parentOf = new HashMap<>();
        for (ParsedTable.Row row : rows) {
            String code = row.values().get(codeCol);
            if (code != null && parentCol != null && row.values().get(parentCol) != null) {
                parentOf.put(code, row.values().get(parentCol));
            }
        }
        Map<String, String> typeOf = deptTypes(rows, codeCol, f2c.get("deptType"), parentOf);
        Map<String, List<String>> headCandidates = headCandidates(roster, codeByLabel, typeOf);

        if (headCol == null) {
            long found = typeOf.keySet().stream().filter(c -> headCandidates.getOrDefault(c, List.of()).size() == 1).count();
            return List.of(new ValidationIssue(file.getFileId(), null, "headEmpNo", null, Severity.WARN, IssueCode.HEAD_COLUMN_MISSING,
                    "부서장 사번 열이 없습니다. 부서장이 비어 있으면 결재선 자동 결정이 안 됩니다. 직원명부 직책으로 "
                            + typeOf.size() + "개 부서 중 " + found + "개는 정할 수 있으니, 부서장 사번 열을 추가해 주세요", null, null));
        }

        List<ValidationIssue> issues = new ArrayList<>();
        for (ParsedTable.Row row : rows) {
            String head = row.values().get(headCol);
            String code = row.values().get(codeCol);
            if (head == null) {
                if (code != null) {
                    issues.add(vacant(file, row, headCol, roster, headCandidates.getOrDefault(code, List.of()), headDuty(typeOf.get(code))));
                }
                continue;
            }
            String empNo = head;
            if (!roster.containsKey(head)) {
                List<String> byName = empNosByName.getOrDefault(head, List.of());
                if (byName.size() != 1) {
                    issues.add(issue(file, row, headCol, Severity.WARN, IssueCode.HEAD_NOT_IN_ROSTER,
                            "부서장 '" + head + "'이(가) 직원명부에 없습니다" + (byName.size() > 1 ? " (같은 이름이 " + byName.size() + "명)" : "")
                                    + ". 부서장 열에는 사번을 적어 주세요", head, null));
                    continue;
                }
                empNo = byName.get(0);
                issues.add(issue(file, row, headCol, Severity.FIX, IssueCode.HEAD_NAME_GIVEN,
                        "부서장에 이름이 적혀 있습니다. 직원명부로 보면 사번 '" + empNo + "'입니다", head, empNo));
            }
            Member m = roster.get(empNo);
            if (m.duplicated()) {
                continue;
            }
            String who = (m.name() == null ? "" : m.name()) + "(" + empNo + ")";
            if (!m.employed()) {
                issues.add(issue(file, row, headCol, Severity.WARN, IssueCode.HEAD_NOT_EMPLOYED,
                        "부서장 " + who + "은(는) 직원명부에서 재직 중이 아닙니다. 재직자만 부서장이 될 수 있습니다", head, null));
            }
            String memberDept = m.dept() == null ? null : codeByLabel.get(m.dept());
            if (memberDept != null && code != null && !inSubtree(memberDept, code, parentOf)) {
                issues.add(issue(file, row, headCol, Severity.WARN, IssueCode.HEAD_OUTSIDE_DEPT,
                        "부서장 " + who + "의 명부 소속은 '" + m.dept() + "'입니다. 이 부서나 하위 부서 소속만 부서장이 될 수 있습니다 (겸직이면 소속을 확인해 주세요)",
                        head, null));
            }
        }
        return issues;
    }

    /** 조직 파일의 부서코드·부서명 → 부서코드. 같은 이름의 부서가 둘이면 그 이름으로는 찾지 않는다 */
    public static Map<String, String> codeByLabel(List<ParsedTable.Row> rows, String codeCol, String nameCol) {
        Map<String, String> out = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (ParsedTable.Row row : rows) {
            String code = row.values().get(codeCol);
            if (code == null) {
                continue;
            }
            out.put(code, code);
            String name = nameCol == null ? null : row.values().get(nameCol);
            if (name != null && out.putIfAbsent(name, code) != null && !code.equals(out.get(name))) {
                ambiguous.add(name);
            }
        }
        ambiguous.forEach(out::remove);
        return out;
    }

    /** 부서 유형에 맞는 부서장 직책. Map.of는 null 키를 못 받는다 */
    private static String headDuty(String type) {
        return type == null ? null : HEAD_DUTY.get(type);
    }

    /** 비어 있는 부서장: 후보가 한 명이면 그 사번을 제안, 아니면 공석 경고 */
    private static ValidationIssue vacant(MigrationFile file, ParsedTable.Row row, String headCol, Map<String, Member> roster,
                                          List<String> candidates, String duty) {
        if (candidates.size() == 1) {
            String empNo = candidates.get(0);
            String name = roster.get(empNo).name();
            return issue(file, row, headCol, Severity.FIX, IssueCode.HEAD_FROM_DUTY,
                    "부서장이 비어 있습니다. 직원명부에서 이 부서의 재직 중인 " + duty + "은(는) " + (name == null ? "" : name) + "(" + empNo + ") 한 명입니다",
                    null, empNo);
        }
        String why = duty == null ? "부서유형을 알 수 없어 후보를 찾지 못했습니다"
                : candidates.isEmpty() ? "직원명부에 이 부서의 재직 중인 " + duty + "이(가) 없습니다"
                : "이 부서의 재직 중인 " + duty + "이(가) " + candidates.size() + "명이라 정할 수 없습니다";
        return issue(file, row, headCol, Severity.WARN, IssueCode.HEAD_VACANT,
                "부서장이 비어 있어 결재선 자동 결정이 안 됩니다. " + why, null, null);
    }

    /** 부서코드 → 부서 유형. 유형 열 값이 맞으면 그 값, 아니면 트리 깊이(최상위 회사, 그 아래 본부, 나머지 팀). 최상위에서 닿지 않으면 없음 */
    private static Map<String, String> deptTypes(List<ParsedTable.Row> rows, String codeCol, String typeCol, Map<String, String> parentOf) {
        Map<String, String> enumValues = EntitySchemas.of(MigrationEntity.HR_ORG)
                .flatMap(s -> s.field("deptType")).orElseThrow().enumValues();
        Set<String> codes = new HashSet<>();
        for (ParsedTable.Row row : rows) {
            if (row.values().get(codeCol) != null) {
                codes.add(row.values().get(codeCol));
            }
        }
        Map<String, String> out = new HashMap<>();
        for (ParsedTable.Row row : rows) {
            String code = row.values().get(codeCol);
            if (code == null) {
                continue;
            }
            String declared = typeCol == null ? null : row.values().get(typeCol);
            String type = declared == null ? null : enumValues.getOrDefault(declared, declared.toUpperCase(Locale.ROOT));
            if (type == null || !HEAD_DUTY.containsKey(type)) {
                int depth = depth(code, codes, parentOf);
                type = depth < 0 ? null : TYPE_BY_DEPTH.get(Math.min(depth, TYPE_BY_DEPTH.size() - 1));
            }
            if (type != null) {
                out.put(code, type);
            }
        }
        return out;
    }

    /** 최상위 부서까지 몇 단계인지. 상위부서가 파일에 없거나 순환이면 -1 */
    private static int depth(String code, Set<String> codes, Map<String, String> parentOf) {
        String d = code;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            String parent = parentOf.get(d);
            if (parent == null) {
                return depth;
            }
            if (!codes.contains(parent)) {
                return -1;
            }
            d = parent;
        }
        return -1;
    }

    /** 부서코드 → 그 부서 소속이면서 유형에 맞는 직책인 재직자 사번 (명부에 두 번 나온 사번은 뺀다) */
    private static Map<String, List<String>> headCandidates(Map<String, Member> roster, Map<String, String> codeByLabel,
                                                            Map<String, String> typeOf) {
        Map<String, List<String>> out = new HashMap<>();
        roster.forEach((empNo, m) -> {
            String code = m.dept() == null ? null : codeByLabel.get(m.dept());
            if (code != null && m.employed() && !m.duplicated() && m.duty() != null && m.duty().equals(headDuty(typeOf.get(code)))) {
                out.computeIfAbsent(code, k -> new ArrayList<>()).add(empNo);
            }
        });
        out.values().forEach(l -> l.sort(null));
        return out;
    }

    /** dept가 root이거나 그 하위 부서인지. 위로 올라가며 찾는다 */
    private static boolean inSubtree(String dept, String root, Map<String, String> parentOf) {
        Set<String> visited = new HashSet<>();
        for (String d = dept; d != null && visited.size() < MAX_DEPTH && visited.add(d); d = parentOf.get(d)) {
            if (d.equals(root)) {
                return true;
            }
        }
        return false;
    }

    /** 같은 작업의 가장 최근 직원명부: 사번 → 이름·부서·직책·재직 여부 */
    private Optional<Map<String, Member>> roster(Long migrationId) {
        Optional<MigrationFile> file = fileRepository.findByMigrationIdOrderByFileIdAsc(migrationId).stream()
                .filter(f -> f.getEntity() == MigrationEntity.HR_EMPLOYEE)
                .max(Comparator.comparing(MigrationFile::getFileId));
        if (file.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> f2c = mappingService.fieldToColumn(file.get().getFileId());
        String empCol = f2c.get("empNo");
        if (empCol == null) {
            return Optional.empty();
        }
        Map<String, String> statusCodes = EntitySchemas.of(MigrationEntity.HR_EMPLOYEE)
                .flatMap(s -> s.field("status")).orElseThrow().enumValues();
        LocalDate today = LocalDate.now();
        Map<String, Member> out = new HashMap<>();
        for (ParsedTable.Row r : stagingRowRepository.findByFileId(file.get().getFileId())) {
            String emp = r.values().get(empCol);
            if (emp == null) {
                continue;
            }
            // 재직상태를 알아볼 수 없으면(Y/N, 0/1 …) 퇴사일만 본다
            String status = value(r, f2c.get("status"));
            String statusCode = status == null ? null : statusCodes.getOrDefault(status, status.toUpperCase(Locale.ROOT));
            boolean notEmployed = statusCode != null && statusCodes.containsValue(statusCode) && !statusCode.equals("EMPLOYED");
            boolean resigned = FieldValueChecker.parseDate(value(r, f2c.get("resignDate"))).map(d -> !d.isAfter(today)).orElse(false);
            Member m = new Member(value(r, f2c.get("name")), value(r, f2c.get("dept")), value(r, f2c.get("duty")), !notEmployed && !resigned, false);
            out.merge(emp, m, (a, b) -> new Member(a.name(), a.dept(), a.duty(), a.employed(), true));
        }
        return Optional.of(out);
    }

    private static String value(ParsedTable.Row row, String column) {
        return column == null ? null : row.values().get(column);
    }

    private static ValidationIssue issue(MigrationFile file, ParsedTable.Row row, String column, Severity severity, IssueCode code,
                                         String message, String value, String suggestion) {
        return new ValidationIssue(file.getFileId(), row.rowNo(), "headEmpNo", column, severity, code, message, value, suggestion);
    }
}
