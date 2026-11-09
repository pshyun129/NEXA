package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.mapping.EnumValueSuggester;
import com.ssafy.migrationservice.domain.migration.mapping.LlmMappingAdvisor;
import com.ssafy.migrationservice.domain.migration.mapping.MappingService;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.domain.migration.validation.formula.FormulaRuleService;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueRepository;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import com.ssafy.migrationservice.domain.migration.validation.payroll.LegalStandardChecker;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchema;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchemas;
import com.ssafy.migrationservice.domain.migration.validation.schema.FieldSpec;
import com.ssafy.migrationservice.domain.migration.validation.schema.FieldType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 파일 하나를 단계별로 검증하고 결과를 validation_issue에 덮어쓴다.
 * ⓪ 엑셀 구조(합계 행·수식 오류·수식 규칙에서 벗어난 칸·합계 열 검산)·급여 법정 기준·⑤ 직원명부 참조·부양가족(공제가족수)·연봉이력(급여 기본급·연봉)·집계(근무표·출역부 ↔ 급여·신고)·조직 부서장(직원명부) 파일 사이 대조·근무표 (모든 entity) → ① 파일 구조 → ② 형식·③ 필수값 → ④ 파일 안 중복 → ⑥ 업무 규칙
 * 앞 단계에서 형식이 틀린 값은 업무 규칙에서 쓰지 않는다 (오류가 번지지 않게).
 */
@Service
@RequiredArgsConstructor
public class ValidationService {

    // 부서유형 단계 순서 (user-service DeptType 선언 순서: 앞이 높은 단계)
    private static final List<String> DEPT_TYPES = List.of("COMPANY", "DIVISION", "TEAM");

    private final MappingService mappingService;
    private final StagingRowRepository stagingRowRepository;
    private final IssueRepository issueRepository;
    private final FormulaRuleService formulaRuleService;
    private final RosterReferenceChecker rosterReferenceChecker;
    private final EnumValueSuggester enumValueSuggester;
    private final DependentsChecker dependentsChecker;
    private final SalaryHistoryChecker salaryHistoryChecker;
    private final CrossFileTotalsChecker crossFileTotalsChecker;
    private final DeptHeadChecker deptHeadChecker;
    private final PayGradeBaseChecker payGradeBaseChecker;

    @Transactional
    public void validate(MigrationFile file) {
        List<ValidationIssue> issues = new ArrayList<>();
        List<ParsedTable.Row> rows = stagingRowRepository.findByFileId(file.getFileId());
        sheetStructure(file, rows, issues);
        issues.addAll(formulaRuleService.inspect(file, rows).issues());
        issues.addAll(LegalStandardChecker.check(file.getFileId(), file.getSheetName(), file.getFileName(), List.of(file.getHeaders()), rows));
        issues.addAll(rosterReferenceChecker.check(file, rows));
        issues.addAll(PayrollRowChecker.check(file, rows));
        issues.addAll(dependentsChecker.check(file, rows));
        issues.addAll(salaryHistoryChecker.check(file, rows));
        issues.addAll(crossFileTotalsChecker.check(file, rows));
        issues.addAll(DutyRosterChecker.check(file, rows));
        issues.addAll(deptHeadChecker.check(file, rows));
        issues.addAll(payGradeBaseChecker.check(file, rows));
        Optional<EntitySchema> schemaOpt = EntitySchemas.of(file.getEntity());
        if (schemaOpt.isEmpty()) {
            if (file.getEntity() != MigrationEntity.REFERENCE) {
                issues.add(fileIssue(file, Severity.WARN, IssueCode.SCHEMA_NOT_READY,
                        file.getEntity() + " 검증 규칙은 준비 중입니다"));
            }
            issueRepository.replaceForFile(file.getMigrationId(), file.getFileId(), issues);
            return;
        }
        EntitySchema schema = schemaOpt.get();
        Map<String, String> fieldToColumn = mappingService.fieldToColumn(file.getFileId());

        // ① 파일 구조: 필수 필드가 어느 컬럼에도 연결되지 않음 → 한 건으로 묶어 보여준다
        //   사원 직급은 열이 없어도 직위가 있으면 직위를 직급으로 쓴다 (회사 안에는 직급이 있고 파일에만 없는 경우)
        boolean gradeFromPosition = file.getEntity() == MigrationEntity.HR_EMPLOYEE
                && !fieldToColumn.containsKey("grade") && fieldToColumn.containsKey("position");
        List<String> missing = schema.fields().stream()
                .filter(f -> f.required() && !fieldToColumn.containsKey(f.name()))
                .filter(f -> !(gradeFromPosition && f.name().equals("grade")))
                .map(FieldSpec::label)
                .toList();
        if (file.getEntity() == MigrationEntity.HR_EMPLOYEE) {
            issues.addAll(GradeStepChecker.check(file, fieldToColumn, rows, gradeFromPosition));
        }
        if (!missing.isEmpty()) {
            String labels = String.join(", ", missing);
            issues.add(new ValidationIssue(file.getFileId(), null, null, null, Severity.BLOCK,
                    IssueCode.MISSING_REQUIRED_COLUMN,
                    "필수 컬럼 " + missing.size() + "개가 없습니다: " + labels + ". 컬럼을 추가하거나 매핑에서 연결해 주세요",
                    labels, null));
        }

        // ② 형식 · ③ 필수값. 형식이 맞는 날짜만 모아 업무 규칙에 넘긴다
        Map<String, Map<String, LlmMappingAdvisor.Advice>> enumAdvice = enumAdvice(file, schema, fieldToColumn, rows);
        Map<Integer, Map<String, LocalDate>> validDates = new HashMap<>();
        for (ParsedTable.Row row : rows) {
            for (Map.Entry<String, String> e : fieldToColumn.entrySet()) {
                FieldSpec f = schema.field(e.getKey()).orElseThrow();
                String column = e.getValue();
                String value = row.values().get(column);
                if (value == null) {
                    if (f.required()) {
                        issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), f.name(), column, Severity.BLOCK,
                                IssueCode.REQUIRED, Josa.iGa(f.label()) + " 비어 있습니다", null, null));
                    }
                    continue;
                }
                Optional<FieldValueChecker.Finding> finding = FieldValueChecker.check(f, value);
                LlmMappingAdvisor.Advice advice = enumAdvice.getOrDefault(f.name(), Map.of()).get(value);
                if (finding.isPresent() && finding.get().code() == IssueCode.INVALID_ENUM && advice != null) {
                    // 허용 값이 아니지만 LLM이 뜻을 추천한 값: 자동 정리 제안으로 (사람이 적용해야 바뀜)
                    finding = Optional.of(new FieldValueChecker.Finding(Severity.FIX, IssueCode.ENUM_VALUE_SUGGESTED,
                            f.label() + " '" + value + "'을(를) '" + advice.field() + "'(으)로 바꿉니다 (LLM 추천: " + advice.reason() + ")",
                            advice.field()));
                } else if (finding.isPresent() && finding.get().code() == IssueCode.INVALID_ENUM) {
                    // LLM 이 꺼져 있거나 답이 없어도, 허용 값 하나의 앞부분이면(정규 → 정규직) 그 값을 제안한다
                    Optional<String> prefix = FieldValueChecker.enumPrefixMatch(f, value);
                    if (prefix.isPresent()) {
                        finding = Optional.of(new FieldValueChecker.Finding(Severity.FIX, IssueCode.ENUM_VALUE_SUGGESTED,
                                f.label() + " '" + value + "'을(를) '" + prefix.get() + "'(으)로 바꿉니다 (허용 값 '" + prefix.get() + "'의 줄임말)",
                                prefix.get()));
                    }
                }
                finding.ifPresent(fd -> issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), f.name(), column,
                        fd.severity(), fd.code(), fd.message(), value, fd.suggestion())));
                if (f.type() == FieldType.DATE && finding.map(fd -> fd.severity() != Severity.BLOCK).orElse(true)) {
                    FieldValueChecker.parseDate(value)
                            .ifPresent(d -> validDates.computeIfAbsent(row.rowNo(), k -> new HashMap<>()).put(f.name(), d));
                }
            }
        }

        // ②-1 코드성 필드 오타 의심: 드문 값이 흔한 값과 자모 하나만 다름
        for (Map.Entry<String, String> e : fieldToColumn.entrySet()) {
            FieldSpec f = schema.field(e.getKey()).orElseThrow();
            if (!f.codeLike()) {
                continue;
            }
            String column = e.getValue();
            Map<String, CodeValueChecker.Suspect> suspects = CodeValueChecker.suspects(rows, column);
            for (ParsedTable.Row row : rows) {
                CodeValueChecker.Suspect s = suspects.get(row.values().get(column));
                if (s != null) {
                    issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), f.name(), column, Severity.WARN,
                            IssueCode.CODE_TYPO_SUSPECTED, f.label() + " 값 '" + s.value() + "'은(는) '" + s.likely() + "'("
                            + s.likelyCount() + "건)의 오타일 수 있습니다", s.value(), s.likely()));
                }
            }
        }

        // ④ 파일 안 중복: 기준 키는 SQL로 한 번에
        for (FieldSpec key : schema.keyFields()) {
            String column = fieldToColumn.get(key.name());
            if (column == null) {
                continue;
            }
            stagingRowRepository.findDuplicates(file.getFileId(), column).forEach((value, rowNos) -> {
                String where = rowNos.stream().map(String::valueOf).collect(Collectors.joining(", "));
                for (Integer rowNo : rowNos) {
                    issues.add(new ValidationIssue(file.getFileId(), rowNo, key.name(), column, Severity.BLOCK,
                            IssueCode.DUPLICATE_KEY, key.label() + " 중복: '" + value + "' (" + where + "행)",
                            value, null));
                }
            });
        }

        // ⑥ 업무 규칙
        if (file.getEntity() == MigrationEntity.HR_EMPLOYEE) {
            employeeRules(file, rows, fieldToColumn, validDates, issues);
        } else if (file.getEntity() == MigrationEntity.HR_ORG) {
            orgRules(file, rows, fieldToColumn, issues);
        }

        issueRepository.replaceForFile(file.getMigrationId(), file.getFileId(), issues);
    }

    private void employeeRules(MigrationFile file, List<ParsedTable.Row> rows, Map<String, String> fieldToColumn,
                               Map<Integer, Map<String, LocalDate>> validDates, List<ValidationIssue> issues) {
        for (ParsedTable.Row row : rows) {
            Map<String, LocalDate> d = validDates.getOrDefault(row.rowNo(), Map.of());
            LocalDate hire = d.get("hireDate");
            LocalDate resign = d.get("resignDate");
            LocalDate recog = d.get("recogHireDate");
            if (hire != null && resign != null && resign.isBefore(hire)) {
                issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), "resignDate", fieldToColumn.get("resignDate"),
                        Severity.BLOCK, IssueCode.DATE_ORDER, "퇴사일이 입사일보다 빠릅니다", resign.toString(), null));
            }
            if (hire != null && recog != null && recog.isAfter(hire)) {
                issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), "recogHireDate", fieldToColumn.get("recogHireDate"),
                        Severity.WARN, IssueCode.DATE_ORDER, "인정입사일이 입사일보다 늦습니다", recog.toString(), null));
            }
        }

        // 같은 사람이 두 번 들어갔는지: 이름 + 생년월일이 같으면 경고 (동명이인일 수 있어 차단하지 않음)
        String nameCol = fieldToColumn.get("name");
        String birthCol = fieldToColumn.get("birthDate");
        if (nameCol == null || birthCol == null) {
            return;
        }
        Map<String, List<Integer>> byPerson = new LinkedHashMap<>();
        for (ParsedTable.Row row : rows) {
            String name = row.values().get(nameCol);
            LocalDate birth = validDates.getOrDefault(row.rowNo(), Map.of()).get("birthDate");
            if (name != null && birth != null) {
                byPerson.computeIfAbsent(name + "|" + birth, k -> new ArrayList<>()).add(row.rowNo());
            }
        }
        byPerson.forEach((person, rowNos) -> {
            if (rowNos.size() < 2) {
                return;
            }
            String where = rowNos.stream().map(String::valueOf).collect(Collectors.joining(", "));
            String name = person.substring(0, person.indexOf('|'));
            for (Integer rowNo : rowNos) {
                issues.add(new ValidationIssue(file.getFileId(), rowNo, "name", nameCol, Severity.WARN,
                        IssueCode.DUPLICATE_SUSPECTED, "이름·생년월일이 같은 직원이 있습니다 (" + where + "행). 동명이인인지 확인해 주세요",
                        name, null));
            }
        });
    }

    private void orgRules(MigrationFile file, List<ParsedTable.Row> rows, Map<String, String> fieldToColumn,
                          List<ValidationIssue> issues) {
        String codeCol = fieldToColumn.get("deptCode");
        String parentCol = fieldToColumn.get("parentDeptCode");
        if (codeCol == null || parentCol == null) {
            return;
        }
        Set<String> codes = new HashSet<>();
        rows.forEach(r -> Optional.ofNullable(r.values().get(codeCol)).ifPresent(codes::add));
        for (ParsedTable.Row row : rows) {
            String code = row.values().get(codeCol);
            String parent = row.values().get(parentCol);
            if (parent == null) {
                continue;
            }
            if (parent.equals(code)) {
                issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), "parentDeptCode", parentCol, Severity.BLOCK,
                        IssueCode.SELF_PARENT, "자기 자신을 상위부서로 지정했습니다", parent, null));
            } else if (!codes.contains(parent)) {
                issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), "parentDeptCode", parentCol, Severity.WARN,
                        IssueCode.PARENT_NOT_IN_FILE, "이 파일에 없는 상위부서입니다: '" + parent + "'. 기존 부서인지 확인해 주세요",
                        parent, null));
            }
        }
        deptTypeOrder(file, rows, fieldToColumn, issues);
    }

    /**
     * 부서유형 단계 (user-service 부서 유형 규칙과 같게): 최상위 부서만 회사, 하위 부서는 상위보다 높은 단계일 수 없음 (같은 단계는 됨).
     * 허용 값이 아닌 유형은 형식 단계에서 이미 나오므로 여기서는 건너뛴다
     */
    private static void deptTypeOrder(MigrationFile file, List<ParsedTable.Row> rows, Map<String, String> fieldToColumn,
                                      List<ValidationIssue> issues) {
        String typeCol = fieldToColumn.get("deptType");
        if (typeCol == null) {
            return;
        }
        String codeCol = fieldToColumn.get("deptCode");
        String parentCol = fieldToColumn.get("parentDeptCode");
        FieldSpec spec = EntitySchemas.of(MigrationEntity.HR_ORG).flatMap(s -> s.field("deptType")).orElseThrow();
        Map<String, String> typeByCode = new HashMap<>();
        rows.forEach(r -> deptType(spec, r.values().get(typeCol))
                .ifPresent(t -> typeByCode.put(r.values().get(codeCol), t)));
        for (ParsedTable.Row row : rows) {
            Optional<String> type = deptType(spec, row.values().get(typeCol));
            if (type.isEmpty()) {
                continue;
            }
            String parent = row.values().get(parentCol);
            String parentType = parent == null ? null : typeByCode.get(parent);
            String message = parent == null && !type.get().equals("COMPANY") ? "최상위 부서는 부서유형이 회사여야 합니다"
                    : parent != null && type.get().equals("COMPANY") ? "회사 아래에 또 회사를 둘 수 없습니다. 본부나 팀으로 바꿔 주세요"
                    : parentType != null && DEPT_TYPES.indexOf(type.get()) < DEPT_TYPES.indexOf(parentType)
                    ? "상위부서 '" + parent + "'보다 높은 단계입니다 (회사 > 본부 > 팀)" : null;
            if (message != null) {
                issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), "deptType", typeCol, Severity.WARN,
                        IssueCode.DEPT_TYPE_ORDER, message, row.values().get(typeCol), null));
            }
        }
    }

    /** '본부' → DIVISION, 'TEAM' → TEAM, 허용 값이 아니면 비어 있음 */
    private static Optional<String> deptType(FieldSpec spec, String value) {
        if (value == null) {
            return Optional.empty();
        }
        String code = spec.enumValues().getOrDefault(value, value.toUpperCase(Locale.ROOT));
        return DEPT_TYPES.contains(code) ? Optional.of(code) : Optional.empty();
    }

    /** 코드성(ENUM) 필드마다 허용 값이 아닌 값들 → LLM 추천 (파일·필드·값마다 한 번만 묻고 저장된 것을 씀) */
    private Map<String, Map<String, LlmMappingAdvisor.Advice>> enumAdvice(MigrationFile file, EntitySchema schema,
                                                                         Map<String, String> fieldToColumn, List<ParsedTable.Row> rows) {
        Map<String, Map<String, LlmMappingAdvisor.Advice>> out = new HashMap<>();
        fieldToColumn.forEach((name, column) -> {
            FieldSpec f = schema.field(name).orElseThrow();
            if (f.type() != FieldType.ENUM) {
                return;
            }
            Set<String> unknown = new HashSet<>();
            for (ParsedTable.Row row : rows) {
                String v = row.values().get(column);
                if (v != null && FieldValueChecker.check(f, v).map(fd -> fd.code() == IssueCode.INVALID_ENUM).orElse(false)) {
                    unknown.add(v);
                }
            }
            if (!unknown.isEmpty()) {
                out.put(name, enumValueSuggester.suggest(file.getFileId(), f, unknown));
            }
        });
        return out;
    }

    /** ⓪ 엑셀 구조: 합계 행, 합계 아래 행, 저장된 값이 오류인 수식 셀. 스키마가 없어도 본다 */
    private void sheetStructure(MigrationFile file, List<ParsedTable.Row> rows, List<ValidationIssue> issues) {
        if (file.getTotalRows().length > 0) {
            String where = Arrays.stream(file.getTotalRows()).map(String::valueOf).collect(Collectors.joining(", "));
            issues.add(fileIssue(file, Severity.WARN, IssueCode.TOTAL_ROW_SKIPPED,
                    "합계·소계 행 " + file.getTotalRows().length + "개(" + where + "행)는 데이터에서 뺐습니다"));
        }
        for (Integer rowNo : file.getRowsAfterTotal()) {
            issues.add(new ValidationIssue(file.getFileId(), rowNo, null, null, Severity.WARN, IssueCode.ROW_AFTER_TOTAL,
                    "합계 행 아래에 있는 행입니다. 합계에 빠졌을 수 있으니 확인해 주세요", null, null));
        }
        for (ParsedTable.Row row : rows) {
            row.errors().forEach((column, error) -> issues.add(new ValidationIssue(file.getFileId(), row.rowNo(), null, column,
                    Severity.BLOCK, IssueCode.FORMULA_ERROR,
                    "'" + column + "' 수식 결과가 오류(" + error + ")입니다. 원본 엑셀에서 수식이 참조하는 칸을 확인해 주세요", error, null)));
        }
    }

    private static ValidationIssue fileIssue(MigrationFile file, Severity severity, IssueCode code, String message) {
        return new ValidationIssue(file.getFileId(), null, null, null, severity, code, message, null, null);
    }
}
