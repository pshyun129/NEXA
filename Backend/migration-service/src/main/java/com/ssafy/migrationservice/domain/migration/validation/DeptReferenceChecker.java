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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 사원의 부서가 같은 작업의 조직 파일에 있는지. 없으면 적재 때 대상에서 부서를 못 찾거나 엉뚱한 부서로 들어가므로 막는다.
 * 회사 전체를 옮기는 이관이라 조직 파일을 기준으로 본다 (대상에만 있고 조직 파일에 없는 부서도 막힌다 — 조직 파일에 넣어 달라고 안내).
 * 조직 파일이 아직 없으면 파일 단위로 한 번 알리고 행은 보지 않는다. 조직 파일을 나중에 올리면 MigrationService 가 사원 파일을 다시 검증한다.
 */
@Component
@RequiredArgsConstructor
public class DeptReferenceChecker {

    private final MigrationFileRepository fileRepository;
    private final StagingRowRepository stagingRowRepository;
    private final MappingService mappingService;

    public List<ValidationIssue> check(MigrationFile file, List<ParsedTable.Row> rows) {
        if (file.getEntity() != MigrationEntity.HR_EMPLOYEE) {
            return List.of();
        }
        String deptCol = mappingService.fieldToColumn(file.getFileId()).get("dept");
        if (deptCol == null) {
            return List.of();
        }
        Optional<Set<String>> labels = orgLabels(file.getMigrationId());
        if (labels.isEmpty()) {
            return List.of(new ValidationIssue(file.getFileId(), null, "dept", deptCol, Severity.WARN, IssueCode.ORG_FILE_MISSING,
                    "조직 파일이 없거나 부서코드·부서명이 연결되지 않아 사원의 부서를 확인하지 못했습니다. 조직 파일을 올리면 다시 확인합니다", null, null));
        }
        List<ValidationIssue> issues = new ArrayList<>();
        for (ParsedTable.Row r : rows) {
            String dept = r.values().get(deptCol);
            if (dept != null && !dept.isBlank() && !labels.get().contains(dept.strip())) {
                issues.add(new ValidationIssue(file.getFileId(), r.rowNo(), "dept", deptCol, Severity.BLOCK, IssueCode.DEPT_NOT_IN_ORG,
                        "부서 '" + dept + "'이(가) 조직 파일에 없습니다. 조직 파일에 부서를 추가하거나 부서 이름을 고쳐 주세요", dept, null));
            }
        }
        return issues;
    }

    /** 가장 최근 조직 파일의 부서코드·부서명 (같은 이름이 둘이어도 '있는 이름'으로 본다). 조직 파일이 없거나 부서코드·부서명이 연결되지 않았으면 empty */
    private Optional<Set<String>> orgLabels(Long migrationId) {
        Optional<MigrationFile> org = fileRepository.findByMigrationIdOrderByFileIdAsc(migrationId).stream()
                .filter(f -> f.getEntity() == MigrationEntity.HR_ORG)
                .max(Comparator.comparing(MigrationFile::getFileId));
        if (org.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> f2c = mappingService.fieldToColumn(org.get().getFileId());
        String codeCol = f2c.get("deptCode");
        String nameCol = f2c.get("name");
        if (codeCol == null && nameCol == null) {
            return Optional.empty();
        }
        Set<String> labels = new HashSet<>();
        for (ParsedTable.Row r : stagingRowRepository.findByFileId(org.get().getFileId())) {
            for (String col : new String[]{codeCol, nameCol}) {
                String v = col == null ? null : r.values().get(col);
                if (v != null && !v.isBlank()) {
                    labels.add(v.strip());
                }
            }
        }
        return Optional.of(labels);
    }
}
