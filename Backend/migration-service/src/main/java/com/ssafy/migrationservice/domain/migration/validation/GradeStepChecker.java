package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.load.GradeResolver;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사원 직급을 NEXA 직급(이름 + 호봉)으로 바꿀 때 짐작이 들어가는 곳을 파일 단위로 한 번 알린다 (적재 규칙은 GradeResolver).
 * - 직급 열이 없어 직위를 직급으로 씀
 * - 호봉이 없는 직급: NEXA에 없으면 1호봉, 하나뿐이면 그 호봉으로 넣음 (여럿이면 기준정보 확인에서 적재를 막는다)
 */
final class GradeStepChecker {

    private static final int EXAMPLES = 5;

    private GradeStepChecker() {
    }

    static List<ValidationIssue> check(MigrationFile file, Map<String, String> fieldToColumn, List<ParsedTable.Row> rows,
                                       boolean gradeFromPosition) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (gradeFromPosition) {
            issues.add(new ValidationIssue(file.getFileId(), null, "grade", fieldToColumn.get("position"), Severity.WARN,
                    IssueCode.GRADE_FROM_POSITION,
                    "직급 열이 없어 직위를 직급으로 씁니다 (예: 과장 → 과장 1호봉). 회사의 직급이 따로 있으면 직급 열을 추가하거나 매핑해 주세요",
                    null, null));
        }
        Set<String> noStep = new LinkedHashSet<>();
        for (ParsedTable.Row r : rows) {
            GradeResolver.Raw raw = GradeResolver.raw(fieldToColumn, r);
            if (raw != null && raw.level() == null) {
                noStep.add(raw.name());
            }
        }
        if (!noStep.isEmpty()) {
            String examples = String.join(", ", noStep.stream().limit(EXAMPLES).toList()) + (noStep.size() > EXAMPLES ? " 외" : "");
            issues.add(new ValidationIssue(file.getFileId(), null, "grade",
                    fieldToColumn.getOrDefault("grade", fieldToColumn.get("position")), Severity.WARN, IssueCode.GRADE_STEP_DEFAULTED,
                    "호봉이 없는 직급이 " + noStep.size() + "개 있습니다 (" + examples + "). NEXA 직급은 호봉이 있어야 해서, "
                            + "NEXA에 없는 직급은 1호봉으로, 하나뿐이면 그 호봉으로 넣습니다. 호봉이 여럿인 직급은 고를 수 없어 호봉까지 적어야 합니다 "
                            + "(적재 전 기준정보 확인에서 알려 줍니다). 호봉 열이 있으면 매핑해 주세요",
                    null, null));
        }
        return issues;
    }
}
