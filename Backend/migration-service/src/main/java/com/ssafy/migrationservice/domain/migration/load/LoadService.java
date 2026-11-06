package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.dto.BatchView;
import com.ssafy.migrationservice.domain.migration.dto.LoadPreviewResponse;
import com.ssafy.migrationservice.domain.migration.dto.LoadResponse;
import com.ssafy.migrationservice.domain.migration.dto.ReferencePlanResponse;
import com.ssafy.migrationservice.domain.migration.dto.ReconciliationItem;
import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.entity.MigrationJob;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.mapping.MappingService;
import com.ssafy.migrationservice.domain.migration.repository.MigrationFileRepository;
import com.ssafy.migrationservice.domain.migration.repository.MigrationJobRepository;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.domain.migration.validation.DeptHeadChecker;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueRepository;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchema;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchemas;
import com.ssafy.migrationservice.global.exception.BusinessException;
import com.ssafy.migrationservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 적재·되돌리기·대사.
 * 대상 서비스 호출이 섞여 있어 메서드 전체를 하나의 DB 트랜잭션으로 묶지 않는다.
 * 묶음 상태는 단계마다 저장해서, 중간에 실패해도 어디까지 됐는지 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoadService {

    private static final int PREVIEW_SAMPLE = 2;

    private final MigrationJobRepository jobRepository;
    private final MigrationFileRepository fileRepository;
    private final MigrationBatchRepository batchRepository;
    private final StagingRowRepository stagingRowRepository;
    private final MappingService mappingService;
    private final IssueRepository issueRepository;
    private final List<TargetLoader> loaders;
    private final HrTargetLoader hrTargetLoader;

    public LoadPreviewResponse preview(Long migrationId, List<Long> fileIds) {
        findEditableJob(migrationId);
        List<MigrationFile> files = selectFiles(migrationId, fileIds);
        long blocks = count(migrationId, files, Severity.BLOCK);
        long fixes = count(migrationId, files, Severity.FIX);
        List<LoadPreviewResponse.Item> items = units(files).stream().map(u -> {
            List<LoadRow> rows = rows(u);
            List<Map<String, Object>> sample = rows.stream().limit(PREVIEW_SAMPLE).map(LoadRow::data).toList();
            return new LoadPreviewResponse.Item(u.file().getFileId(), u.entity(), rows.size(), sample);
        }).toList();
        return new LoadPreviewResponse(blocks == 0 && fixes == 0, blocks, fixes, items);
    }

    /**
     * entity 순서(기준정보 → 조직 → 직원 …)대로 파일마다 묶음을 만들어 넣는다.
     * 기준정보(HR_CODE)는 파일이 아니라 사원 파일에서 뽑아 그 파일 이름으로 따로 묶는다.
     * 한 묶음이 실패하면 그 묶음을 대상 서비스에서 되돌리고, 뒤 순서는 진행하지 않는다 (뒤가 앞에 의존하므로).
     */
    public LoadResponse load(Long migrationId, List<Long> fileIds) {
        MigrationJob job = findEditableJob(migrationId);
        List<MigrationFile> files = selectFiles(migrationId, fileIds);
        long blocks = count(migrationId, files, Severity.BLOCK);
        if (blocks > 0) {
            throw new BusinessException(ErrorCode.BLOCK_ISSUES_EXIST, "차단 오류 " + blocks + "건을 먼저 해결해야 합니다");
        }
        // 임시 테이블 값 = 보낸 값이 되도록, 자동 정리는 적재 전에 반영돼 있어야 한다 (변경 이력에도 남음)
        long fixes = count(migrationId, files, Severity.FIX);
        if (fixes > 0) {
            throw new BusinessException(ErrorCode.FIX_PENDING, "자동 정리 " + fixes + "건을 먼저 적용하세요");
        }
        List<Unit> units = units(files);
        if (units.stream().anyMatch(u -> u.entity() == MigrationEntity.HR_CODE)) {
            String ambiguous = references(migrationId).items().stream()
                    .filter(i -> i.status() == ReferencePlanResponse.Status.AMBIGUOUS)
                    .map(ReferencePlanResponse.Item::message).collect(Collectors.joining(" / "));
            if (!ambiguous.isEmpty()) {
                throw new BusinessException(ErrorCode.REFERENCE_AMBIGUOUS, ambiguous);
            }
        }

        for (Unit unit : units) {
            MigrationFile file = unit.file();
            TargetLoader loader = loaderFor(unit.entity());
            List<LoadRow> rows = rows(unit);
            MigrationBatch batch = batchRepository.save(MigrationBatch.builder()
                    .batchId(newBatchId(file, unit.entity()))
                    .migrationId(migrationId)
                    .fileId(file.getFileId())
                    .entity(unit.entity())
                    .sourceRows(rows.size())
                    .build());
            try {
                LoadResult result = loader.upsert(unit.entity(), batch.getBatchId(), rows);
                batch.loaded(result.created(), result.updated());
                batchRepository.save(batch);
                job.markLoaded();
                jobRepository.save(job);
            } catch (RuntimeException e) {
                log.warn("적재 실패 batch={}: {}", batch.getBatchId(), e.getMessage());
                undoQuietly(loader, batch);
                batch.failed(e.getMessage());
                batchRepository.save(batch);
                break;
            }
        }
        return new LoadResponse(job.getStatus(), batches(migrationId));
    }

    /** 나중 순서의 묶음이 아직 적재돼 있으면 막는다 (예: 직원이 있는데 조직만 되돌리기) */
    public LoadResponse rollback(Long migrationId, String batchId) {
        MigrationJob job = findEditableJob(migrationId);
        MigrationBatch batch = batchRepository.findByBatchIdAndMigrationId(batchId, migrationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BATCH_NOT_FOUND));
        if (batch.getStatus() != BatchStatus.LOADED) {
            throw new BusinessException(ErrorCode.BATCH_NOT_LOADED);
        }
        String dependents = batchRepository.findByMigrationIdOrderByStartedAtAsc(migrationId).stream()
                .filter(b -> b.getStatus() == BatchStatus.LOADED)
                .filter(b -> b.getEntity().ordinal() > batch.getEntity().ordinal())
                .map(MigrationBatch::getBatchId)
                .collect(Collectors.joining(", "));
        if (!dependents.isEmpty()) {
            throw new BusinessException(ErrorCode.ROLLBACK_DEPENDENCY_EXISTS, dependents + "을(를) 먼저 되돌려야 합니다");
        }

        loaderFor(batch.getEntity()).rollback(batch.getEntity(), batchId);
        batch.rolledBack();
        batchRepository.save(batch);
        if (!batchRepository.existsByMigrationIdAndStatus(migrationId, BatchStatus.LOADED)) {
            job.markDraft();
            jobRepository.save(job);
        }
        return new LoadResponse(job.getStatus(), batches(migrationId));
    }

    /**
     * 사원 파일에서 뽑은 기준정보와 대상에 지금 있는 값 비교. 적재 전에 화면에서 보여준다.
     * AMBIGUOUS: 호봉 없이 적힌 직급인데 그 이름의 직급이 호봉별로 여러 개라 사원 적재 때 하나로 못 고르는 값
     */
    public ReferencePlanResponse references(Long migrationId) {
        findJob(migrationId);
        List<MigrationFile> employeeFiles = fileRepository.findByMigrationIdOrderByFileIdAsc(migrationId).stream()
                .filter(f -> f.getEntity() == MigrationEntity.HR_EMPLOYEE)
                .filter(f -> !batchRepository.existsByFileIdAndEntityAndStatus(f.getFileId(), MigrationEntity.HR_CODE, BatchStatus.LOADED))
                .toList();
        if (employeeFiles.isEmpty()) {
            return new ReferencePlanResponse(List.of());
        }
        HrTargetLoader.ExistingReferences existing = hrTargetLoader.existingReferences();
        Set<String> positions = existing.positions().stream().map(HrTargetLoader.Named::name).collect(Collectors.toSet());
        Set<String> duties = existing.duties().stream().map(HrTargetLoader.Named::name).collect(Collectors.toSet());
        Set<String> jobPaths = existing.jobPaths();
        Map<String, Set<Integer>> gradeLevels = new HashMap<>();
        existing.grades().forEach(g -> gradeLevels.computeIfAbsent(g.name(), k -> new HashSet<>()).add(g.level()));
        Set<String> existingGrades = existing.grades().stream().map(g -> g.name() + "|" + g.level()).collect(Collectors.toSet());

        List<ReferencePlanResponse.Item> items = new ArrayList<>();
        for (MigrationFile file : employeeFiles) {
            List<LoadRow> rows = rows(new Unit(file, MigrationEntity.HR_CODE));
            Map<String, Set<Integer>> levels = new HashMap<>();
            gradeLevels.forEach((name, lv) -> levels.put(name, new HashSet<>(lv)));
            rows.stream().filter(r -> ReferenceRows.GRADE.equals(r.data().get("type")))
                    .forEach(r -> levels.computeIfAbsent((String) r.data().get("name"), k -> new HashSet<>()).add((Integer) r.data().get("level")));
            for (LoadRow r : rows) {
                Map<String, Object> d = r.data();
                String type = (String) d.get("type");
                String value;
                boolean exists;
                String message = null;
                switch (type) {
                    case ReferenceRows.POSITION -> {
                        value = (String) d.get("name");
                        exists = positions.contains(value);
                    }
                    case ReferenceRows.GRADE -> {
                        String name = (String) d.get("name");
                        Integer level = (Integer) d.get("level");
                        value = level == null ? name : name + " " + level + "호봉";
                        exists = existingGrades.contains(name + "|" + level);
                        if (level == null && levels.get(name).size() > 1) {
                            message = "직급 '" + name + "'은(는) 호봉이 여러 개라 사원 파일에 호봉까지 적어야 합니다 (예: " + name + " 1호봉)";
                        }
                    }
                    case ReferenceRows.DUTY -> {
                        value = (String) d.get("name");
                        exists = duties.contains(value);
                    }
                    default -> {
                        value = d.get("family") + " > " + d.get("kind") + " > " + d.get("role");
                        exists = jobPaths.contains(value);
                    }
                }
                ReferencePlanResponse.Status status = message != null ? ReferencePlanResponse.Status.AMBIGUOUS
                        : exists ? ReferencePlanResponse.Status.EXISTS : ReferencePlanResponse.Status.NEW;
                items.add(new ReferencePlanResponse.Item(file.getFileId(), type, value, (Integer) d.get("rankOrder"), status, message));
            }
        }
        return new ReferencePlanResponse(items);
    }

    /** 적재된 묶음마다 원본 행 수와 대상 서비스에 들어간 수를 비교 */
    public List<ReconciliationItem> reconcile(Long migrationId) {
        findJob(migrationId);
        return batchRepository.findByMigrationIdOrderByStartedAtAsc(migrationId).stream()
                .filter(b -> b.getStatus() == BatchStatus.LOADED)
                .map(b -> {
                    long loaded = loaderFor(b.getEntity()).summarize(b.getEntity(), b.getBatchId()).count();
                    return new ReconciliationItem(b.getBatchId(), b.getEntity(), "count", b.getSourceRows(), loaded,
                            b.getSourceRows() == loaded);
                })
                .toList();
    }

    public List<BatchView> batches(Long migrationId) {
        return batchRepository.findByMigrationIdOrderByStartedAtAsc(migrationId).stream().map(BatchView::from).toList();
    }

    // ---------- 내부 ----------

    /** fileIds가 비면 적재 가능한 파일 전체(이미 적재된 것·미지원 entity 제외), 있으면 그 파일만 엄격하게 확인 */
    private List<MigrationFile> selectFiles(Long migrationId, List<Long> fileIds) {
        List<MigrationFile> all = fileRepository.findByMigrationIdOrderByFileIdAsc(migrationId);
        List<MigrationFile> selected;
        if (fileIds == null || fileIds.isEmpty()) {
            selected = all.stream()
                    .filter(f -> isSupported(f) && !batchRepository.existsByFileIdAndEntityAndStatus(f.getFileId(), f.getEntity(), BatchStatus.LOADED))
                    .toList();
        } else {
            selected = fileIds.stream().map(id -> all.stream().filter(f -> f.getFileId().equals(id)).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.MIGRATION_FILE_NOT_FOUND))).toList();
            for (MigrationFile f : selected) {
                if (!isSupported(f)) {
                    throw new BusinessException(ErrorCode.LOAD_NOT_SUPPORTED, f.getEntity() + "은(는) 아직 적재를 지원하지 않습니다");
                }
                if (batchRepository.existsByFileIdAndEntityAndStatus(f.getFileId(), f.getEntity(), BatchStatus.LOADED)) {
                    throw new BusinessException(ErrorCode.FILE_ALREADY_LOADED, "이미 적재된 파일입니다: " + f.getFileName());
                }
            }
        }
        if (selected.isEmpty()) {
            throw new BusinessException(ErrorCode.NOTHING_TO_LOAD);
        }
        return selected.stream()
                .sorted(Comparator.comparing((MigrationFile f) -> f.getEntity().ordinal()).thenComparing(MigrationFile::getFileId))
                .toList();
    }

    private boolean isSupported(MigrationFile f) {
        return EntitySchemas.of(f.getEntity()).isPresent() && loaders.stream().anyMatch(l -> l.supports(f.getEntity()));
    }

    private long count(Long migrationId, List<MigrationFile> files, Severity severity) {
        return files.stream().mapToLong(f -> issueRepository.totals(migrationId, f.getFileId()).get(severity)).sum();
    }

    /** 적재 단위: 파일 하나의 entity, 사원 파일이면 그 앞에 기준정보(아직 안 넣었으면). entity 순서로 정렬 */
    private record Unit(MigrationFile file, MigrationEntity entity) {
    }

    private List<Unit> units(List<MigrationFile> files) {
        List<Unit> units = new ArrayList<>();
        for (MigrationFile f : files) {
            if (f.getEntity() == MigrationEntity.HR_EMPLOYEE
                    && !batchRepository.existsByFileIdAndEntityAndStatus(f.getFileId(), MigrationEntity.HR_CODE, BatchStatus.LOADED)) {
                units.add(new Unit(f, MigrationEntity.HR_CODE));
            }
            units.add(new Unit(f, f.getEntity()));
        }
        units.sort(Comparator.comparing((Unit u) -> u.entity().ordinal()));
        return units;
    }

    private List<LoadRow> rows(Unit unit) {
        if (unit.entity() == MigrationEntity.HR_CODE) {
            return ReferenceRows.from(mappingService.fieldToColumn(unit.file().getFileId()),
                    stagingRowRepository.findByFileId(unit.file().getFileId()));
        }
        return transform(unit.file());
    }

    private List<LoadRow> transform(MigrationFile file) {
        EntitySchema schema = EntitySchemas.of(file.getEntity()).orElseThrow();
        List<LoadRow> rows = RowTransformer.transform(schema, mappingService.fieldToColumn(file.getFileId()),
                stagingRowRepository.findByFileId(file.getFileId()));
        if (file.getEntity() != MigrationEntity.HR_EMPLOYEE) {
            return rows;
        }
        // 사원의 부서는 부서명으로 적혀 있는 경우가 많다. 대상에 같은 이름의 기존 부서가 있으면 이름으로는 못 고르므로
        // 같은 작업의 조직 파일에서 찾은 부서코드로 바꿔 보낸다. 못 찾으면 적힌 값 그대로
        Map<String, String> codes = deptCodeByLabel(file.getMigrationId());
        if (codes.isEmpty()) {
            return rows;
        }
        return rows.stream().map(r -> {
            Object dept = r.data().get("dept");
            if (dept == null || !codes.containsKey(dept.toString())) {
                return r;
            }
            Map<String, Object> data = new LinkedHashMap<>(r.data());
            data.put("dept", codes.get(dept.toString()));
            return new LoadRow(r.rowNo(), data);
        }).toList();
    }

    private Map<String, String> deptCodeByLabel(Long migrationId) {
        return fileRepository.findByMigrationIdOrderByFileIdAsc(migrationId).stream()
                .filter(f -> f.getEntity() == MigrationEntity.HR_ORG)
                .max(Comparator.comparing(MigrationFile::getFileId))
                .map(org -> {
                    Map<String, String> f2c = mappingService.fieldToColumn(org.getFileId());
                    String codeCol = f2c.get("deptCode");
                    return codeCol == null ? Map.<String, String>of()
                            : DeptHeadChecker.codeByLabel(stagingRowRepository.findByFileId(org.getFileId()), codeCol, f2c.get("name"));
                })
                .orElse(Map.of());
    }

    private TargetLoader loaderFor(MigrationEntity entity) {
        return loaders.stream().filter(l -> l.supports(entity)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_NOT_SUPPORTED));
    }

    private void undoQuietly(TargetLoader loader, MigrationBatch batch) {
        try {
            loader.rollback(batch.getEntity(), batch.getBatchId());
        } catch (RuntimeException e) {
            log.error("실패 묶음 자동 되돌리기도 실패 batch={}: {}", batch.getBatchId(), e.getMessage());
        }
    }

    private static String newBatchId(MigrationFile file, MigrationEntity entity) {
        return "b-" + file.getMigrationId() + "-" + entity.name().toLowerCase() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
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
}
