package com.ssafy.migrationservice.domain.migration.service;

import com.ssafy.migrationservice.domain.migration.dto.FileDetailResponse;
import com.ssafy.migrationservice.domain.migration.dto.BatchView;
import com.ssafy.migrationservice.domain.migration.dto.FileUploadResponse;
import com.ssafy.migrationservice.domain.migration.dto.FormulaRulesResponse;
import com.ssafy.migrationservice.domain.migration.validation.formula.FormulaRuleService;
import com.ssafy.migrationservice.domain.migration.validation.formula.RuleSummaryService;
import com.ssafy.migrationservice.domain.migration.dto.RuleSummaryResponse;
import com.ssafy.migrationservice.domain.migration.load.BatchStatus;
import com.ssafy.migrationservice.domain.migration.load.MigrationBatchRepository;
import com.ssafy.migrationservice.domain.migration.dto.FixApplyRequest;
import com.ssafy.migrationservice.domain.migration.dto.FixApplyResponse;
import com.ssafy.migrationservice.domain.migration.dto.RowUpdateRequest;
import com.ssafy.migrationservice.domain.migration.dto.RowUpdateResponse;
import com.ssafy.migrationservice.domain.migration.dto.SheetInfo;
import com.ssafy.migrationservice.domain.migration.mapping.ColumnMappingSuggester;
import com.ssafy.migrationservice.domain.migration.validation.FieldValueChecker;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchema;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchemas;
import com.ssafy.migrationservice.domain.migration.validation.schema.FieldSpec;
import com.ssafy.migrationservice.domain.migration.repository.StagingChangeRepository;
import com.ssafy.migrationservice.domain.migration.dto.IssueListResponse;
import com.ssafy.migrationservice.domain.migration.dto.MappingUpdateRequest;
import com.ssafy.migrationservice.domain.migration.dto.MappingUpdateResponse;
import com.ssafy.migrationservice.domain.migration.dto.MigrationCreateRequest;
import com.ssafy.migrationservice.domain.migration.dto.MigrationCreateResponse;
import com.ssafy.migrationservice.domain.migration.dto.MigrationDetailResponse;
import com.ssafy.migrationservice.domain.migration.dto.MigrationListResponse;
import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.entity.MigrationJob;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.mapping.MappingService;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.reader.TableReader;
import com.ssafy.migrationservice.domain.migration.repository.MigrationFileRepository;
import com.ssafy.migrationservice.domain.migration.repository.MigrationJobRepository;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.domain.migration.validation.CrossFileTotalsChecker;
import com.ssafy.migrationservice.domain.migration.validation.DependentsChecker;
import com.ssafy.migrationservice.domain.migration.validation.SalaryHistoryChecker;
import com.ssafy.migrationservice.domain.migration.validation.ValidationService;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueRepository;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.global.exception.BusinessException;
import com.ssafy.migrationservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MigrationService {

    private final MigrationJobRepository jobRepository;
    private final MigrationFileRepository fileRepository;
    private final StagingRowRepository stagingRowRepository;
    private final TableReader tableReader;
    private final MappingService mappingService;
    private final ValidationService validationService;
    private final IssueRepository issueRepository;
    private final StagingChangeRepository stagingChangeRepository;
    private final MigrationBatchRepository batchRepository;
    private final FormulaRuleService formulaRuleService;
    private final RuleSummaryService ruleSummaryService;
    private final DependentsChecker dependentsChecker;
    private final SalaryHistoryChecker salaryHistoryChecker;
    private final CrossFileTotalsChecker crossFileTotalsChecker;

    @Transactional
    public MigrationCreateResponse create(MigrationCreateRequest r) {
        MigrationJob job = jobRepository.save(MigrationJob.builder()
                .companyId(r.companyId())
                .name(r.name())
                .cutoverDate(r.cutoverDate())
                .build());
        return new MigrationCreateResponse(job.getMigrationId(), job.getStatus());
    }

    /** 최근 만든 작업부터 */
    @Transactional(readOnly = true)
    public MigrationListResponse list(int page, int size) {
        Page<MigrationJob> jobs = jobRepository.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "migrationId")));
        List<MigrationListResponse.Item> items = jobs.getContent().stream()
                .map(j -> MigrationListResponse.Item.of(j, fileRepository.countByMigrationId(j.getMigrationId()),
                        batchRepository.countByMigrationIdAndStatus(j.getMigrationId(), BatchStatus.LOADED)))
                .toList();
        return new MigrationListResponse(items, page, size, jobs.getTotalElements());
    }

    @Transactional(readOnly = true)
    public MigrationDetailResponse get(Long migrationId) {
        MigrationJob job = findJob(migrationId);
        return MigrationDetailResponse.of(job, fileRepository.findByMigrationIdOrderByFileIdAsc(migrationId),
                batchRepository.findByMigrationIdOrderByStartedAtAsc(migrationId).stream().map(BatchView::from).toList());
    }

    /**
     * 읽기 → 임시 테이블 저장 → 매핑 추천 → 검증까지 한 번에. 원본 바이트는 저장하지 않는다.
     * 엑셀은 sheet로 시트를 고른다. 없으면 entity 필드와 헤더가 가장 많이 맞는 시트 (스키마가 없으면 첫 시트)
     */
    @Transactional
    public FileUploadResponse upload(Long migrationId, MigrationEntity entity, String fileName, byte[] content, String sheet) {
        MigrationJob job = findEditableJob(migrationId);
        List<ParsedTable> tables = tableReader.readAll(fileName, content);
        ParsedTable table = pickSheet(tables, entity, sheet);

        MigrationFile file = fileRepository.save(MigrationFile.builder()
                .migrationId(job.getMigrationId())
                .entity(entity)
                .fileName(fileName)
                .headers(table.headers().toArray(String[]::new))
                .rowCount(table.rows().size())
                .sheetName(table.sheetName())
                .headerRow(table.headerRowNo())
                .totalRows(table.totalRows().toArray(Integer[]::new))
                .rowsAfterTotal(table.rowsAfterTotal().toArray(Integer[]::new))
                .preamble(table.preamble().isEmpty() ? null : String.join("\n", table.preamble()))
                .build());
        stagingRowRepository.saveAll(file.getFileId(), table.rows());
        mappingService.suggest(file);
        validationService.validate(file);
        // 같은 양식의 다른 시트는 시트끼리 수식 비교 결과가 바뀌므로 다시 검증
        formulaRuleService.siblings(file).forEach(validationService::validate);
        // 월별 급여 시트를 나중에 올려도 부양가족 공제가족수 대조가 되게 다시 검증
        dependentsChecker.related(file).forEach(validationService::validate);
        // 급여 시트 ↔ 연봉이력 ↔ 연도 붙은 연봉 열이 있는 파일(4대보험)도 서로 대조한다
        salaryHistoryChecker.related(file).forEach(validationService::validate);
        // 근무표·출역부 ↔ 급여·신고 집계 대조, 같은 파일 다른 시트의 같은 날 근무
        crossFileTotalsChecker.related(file).forEach(validationService::validate);
        // 직원명부를 새로 올리면 다른 파일의 사번 대조 결과(조직 파일은 부서장)가 바뀐다
        if (entity == MigrationEntity.HR_EMPLOYEE) {
            fileRepository.findByMigrationIdOrderByFileIdAsc(migrationId).stream()
                    .filter(f -> f.getEntity() != MigrationEntity.HR_EMPLOYEE)
                    .forEach(validationService::validate);
        }
        // 조직 파일을 새로 올리면 사원의 부서 확인 결과가 바뀐다
        if (entity == MigrationEntity.HR_ORG) {
            fileRepository.findByMigrationIdOrderByFileIdAsc(migrationId).stream()
                    .filter(f -> f.getEntity() == MigrationEntity.HR_EMPLOYEE)
                    .forEach(validationService::validate);
        }

        return new FileUploadResponse(file.getFileId(), entity, table.rows().size(), table.headers(),
                mappingService.missingRequired(file), issueRepository.totals(migrationId, file.getFileId()),
                table.sheetName(), table.headerRowNo(), table.totalRows(),
                tables.stream().filter(t -> t.sheetName() != null).map(SheetInfo::of).toList());
    }

    private static ParsedTable pickSheet(List<ParsedTable> tables, MigrationEntity entity, String sheet) {
        if (sheet != null && !sheet.isBlank()) {
            return tables.stream().filter(t -> sheet.equals(t.sheetName())).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.SHEET_NOT_FOUND, "시트를 찾을 수 없습니다: " + sheet));
        }
        return EntitySchemas.of(entity)
                .map(schema -> tables.stream()
                        .max(Comparator.comparingLong((ParsedTable t) -> ColumnMappingSuggester.suggest(t.headers(), schema)
                                        .values().stream().filter(Objects::nonNull).count())
                                .thenComparing(t -> -tables.indexOf(t)))
                        .orElseThrow())
                .orElse(tables.get(0));
    }

    /** LLM 호출이 있어 이 메서드에는 트랜잭션을 걸지 않는다 (저장은 RuleSummaryService가 따로) */
    public RuleSummaryResponse ruleSummary(Long migrationId, Long fileId, boolean refresh) {
        return ruleSummaryService.summarize(findFile(migrationId, fileId), refresh);
    }

    /** 수식에서 뽑은 열별 규칙과 벗어난 행 */
    @Transactional(readOnly = true)
    public FormulaRulesResponse formulas(Long migrationId, Long fileId) {
        MigrationFile file = findFile(migrationId, fileId);
        return new FormulaRulesResponse(fileId, file.getSheetName(),
                formulaRuleService.inspect(file, stagingRowRepository.findByFileId(fileId)).columns());
    }

    @Transactional(readOnly = true)
    public FileDetailResponse getFile(Long migrationId, Long fileId) {
        MigrationFile file = findFile(migrationId, fileId);
        List<ParsedTable.Row> rows = stagingRowRepository.findByFileId(fileId);
        return new FileDetailResponse(file.getFileId(), file.getFileName(), file.getSheetName(), file.getHeaderRow(),
                file.getEntity(), file.getRowCount(),
                file.getUploadedAt(), ColumnProfiler.profile(List.of(file.getHeaders()), rows),
                mappingService.view(file), mappingService.missingRequired(file),
                issueRepository.totals(migrationId, fileId));
    }

    /** 매핑을 확정하면 그 파일을 다시 검증한다 */
    @Transactional
    public MappingUpdateResponse updateMappings(Long migrationId, Long fileId, MappingUpdateRequest request) {
        findEditableJob(migrationId);
        MigrationFile file = findEditableFile(migrationId, fileId);
        mappingService.update(file, request);
        validationService.validate(file);
        return new MappingUpdateResponse(mappingService.missingRequired(file), issueRepository.totals(migrationId, fileId));
    }

    /** FIX 이슈의 제안값을 일괄 반영하고, 바뀐 파일을 다시 검증한다 */
    @Transactional
    public FixApplyResponse applyFixes(Long migrationId, FixApplyRequest r) {
        findEditableJob(migrationId);
        String code = r.code() == null ? null : r.code().name();
        List<Long> fileIds = stagingChangeRepository.filesWithFixes(migrationId, r.fileId(), code, r.field());
        fileIds.forEach(id -> findEditableFile(migrationId, id));
        int applied = stagingChangeRepository.applyFixes(migrationId, r.fileId(), code, r.field());
        for (Long fileId : fileIds) {
            validationService.validate(findFile(migrationId, fileId));
        }
        return new FixApplyResponse(applied, issueRepository.totals(migrationId, null),
                r.fileId() == null ? null : issueRepository.totals(migrationId, r.fileId()));
    }

    /** 화면에서 행 하나를 고친다. 그 파일을 다시 검증하고 그 행에 남은 오류를 돌려준다 */
    @Transactional
    public RowUpdateResponse updateRow(Long migrationId, Long fileId, int rowNo, RowUpdateRequest r) {
        findEditableJob(migrationId);
        MigrationFile file = findEditableFile(migrationId, fileId);
        List<String> headers = List.of(file.getHeaders());
        for (String column : r.values().keySet()) {
            if (!headers.contains(column)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "파일에 없는 컬럼입니다: " + column);
            }
        }
        rejectBadFormat(file, rowNo, r.values());
        for (Map.Entry<String, String> e : r.values().entrySet()) {
            String value = e.getValue() == null || e.getValue().isBlank() ? null : e.getValue().strip();
            if (!stagingChangeRepository.updateCell(fileId, rowNo, e.getKey(), value)) {
                throw new BusinessException(ErrorCode.STAGING_ROW_NOT_FOUND);
            }
        }
        validationService.validate(file);
        return new RowUpdateResponse(rowNo, issueRepository.findByRow(fileId, rowNo),
                issueRepository.totals(migrationId, fileId));
    }

    // 행 수정에서 저장하지 않는 형식 오류 (빈 값·자동 정리로 고칠 수 있는 표기는 저장한다)
    private static final Set<IssueCode> BAD_FORMAT = EnumSet.of(IssueCode.INVALID_DATE, IssueCode.INVALID_NUMBER,
            IssueCode.INVALID_EMAIL, IssueCode.INVALID_ENUM, IssueCode.TOO_LONG);

    /** 매핑된 칸에 형식이 틀린 값을 넣으면 저장하지 않고 400 (화면 입력칸이 그대로 남는다) */
    private void rejectBadFormat(MigrationFile file, int rowNo, Map<String, String> values) {
        Optional<EntitySchema> schema = EntitySchemas.of(file.getEntity());
        if (schema.isEmpty()) {
            return;
        }
        Map<String, String> fieldOf = new HashMap<>();
        mappingService.fieldToColumn(file.getFileId()).forEach((field, column) -> fieldOf.put(column, field));
        for (Map.Entry<String, String> e : values.entrySet()) {
            String field = fieldOf.get(e.getKey());
            String value = e.getValue() == null ? null : e.getValue().strip();
            if (field == null || value == null || value.isEmpty()) {
                continue;
            }
            FieldSpec spec = schema.get().field(field).orElseThrow();
            Optional<FieldValueChecker.Finding> finding = FieldValueChecker.check(spec, value);
            if (finding.isPresent() && BAD_FORMAT.contains(finding.get().code())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        rowNo + "행 '" + e.getKey() + "' 값 '" + value + "'을(를) 저장하지 않았습니다: " + finding.get().message());
            }
        }
    }

    @Transactional(readOnly = true)
    public IssueListResponse issues(Long migrationId, Long fileId, Severity severity, int page, int size) {
        findJob(migrationId);
        return new IssueListResponse(
                issueRepository.totals(migrationId, fileId),
                issueRepository.groups(migrationId, fileId, severity),
                issueRepository.page(migrationId, fileId, severity, page, size),
                page, size,
                issueRepository.count(migrationId, fileId, severity));
    }

    private MigrationJob findJob(Long migrationId) {
        return jobRepository.findById(migrationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MIGRATION_NOT_FOUND));
    }

    private MigrationJob findEditableJob(Long migrationId) {
        MigrationJob job = findJob(migrationId);
        if (job.isCompleted()) {
            throw new BusinessException(ErrorCode.MIGRATION_COMPLETED);
        }
        return job;
    }

    /** 적재된 파일은 되돌린 뒤에만 고칠 수 있다 (임시 테이블과 대상 서비스 데이터가 어긋나지 않게) */
    private MigrationFile findEditableFile(Long migrationId, Long fileId) {
        MigrationFile file = findFile(migrationId, fileId);
        if (batchRepository.existsByFileIdAndStatus(fileId, BatchStatus.LOADED)) {
            throw new BusinessException(ErrorCode.FILE_ALREADY_LOADED);
        }
        return file;
    }

    private MigrationFile findFile(Long migrationId, Long fileId) {
        return fileRepository.findByFileIdAndMigrationId(fileId, migrationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MIGRATION_FILE_NOT_FOUND));
    }
}
