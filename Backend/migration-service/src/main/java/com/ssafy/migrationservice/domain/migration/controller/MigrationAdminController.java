package com.ssafy.migrationservice.domain.migration.controller;

import com.ssafy.migrationservice.domain.migration.dto.FileDetailResponse;
import com.ssafy.migrationservice.domain.migration.dto.FileUploadResponse;
import com.ssafy.migrationservice.domain.migration.dto.FormulaRulesResponse;
import com.ssafy.migrationservice.domain.migration.dto.RuleSummaryResponse;
import com.ssafy.migrationservice.domain.migration.dto.FixApplyRequest;
import com.ssafy.migrationservice.domain.migration.dto.FixApplyResponse;
import com.ssafy.migrationservice.domain.migration.dto.IssueListResponse;
import com.ssafy.migrationservice.domain.migration.dto.LoadRequest;
import com.ssafy.migrationservice.domain.migration.dto.LoadResponse;
import com.ssafy.migrationservice.domain.migration.dto.ReconciliationItem;
import com.ssafy.migrationservice.domain.migration.dto.ReferencePlanResponse;
import com.ssafy.migrationservice.domain.migration.load.LoadService;
import org.springframework.web.bind.annotation.DeleteMapping;
import java.util.List;
import com.ssafy.migrationservice.domain.migration.dto.RowUpdateRequest;
import com.ssafy.migrationservice.domain.migration.dto.RowUpdateResponse;
import org.springframework.web.bind.annotation.PatchMapping;
import com.ssafy.migrationservice.domain.migration.dto.MappingUpdateRequest;
import com.ssafy.migrationservice.domain.migration.dto.MappingUpdateResponse;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PutMapping;
import com.ssafy.migrationservice.domain.migration.dto.MigrationCreateRequest;
import com.ssafy.migrationservice.domain.migration.dto.MigrationCreateResponse;
import com.ssafy.migrationservice.domain.migration.dto.MigrationDetailResponse;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.service.MigrationService;
import com.ssafy.migrationservice.global.dto.ApiResult;
import com.ssafy.migrationservice.global.exception.BusinessException;
import com.ssafy.migrationservice.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** 이관 API. 팀 규칙에 따라 관리자 기능은 /api/v1/admin/** 아래에 둔다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/migrations")
public class MigrationAdminController {

    private final MigrationService migrationService;
    private final LoadService loadService;

    @PostMapping
    public ResponseEntity<ApiResult<MigrationCreateResponse>> create(@Valid @RequestBody MigrationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(migrationService.create(request)));
    }

    @GetMapping("/{migrationId}")
    public ResponseEntity<ApiResult<MigrationDetailResponse>> get(@PathVariable Long migrationId) {
        return ResponseEntity.ok(ApiResult.success(migrationService.get(migrationId)));
    }

    @PostMapping(value = "/{migrationId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResult<FileUploadResponse>> upload(@PathVariable Long migrationId,
                                                                @RequestParam MigrationEntity entity,
                                                                @RequestParam(required = false) String sheet,
                                                                @RequestPart MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_READ_FAILED);
        }
        FileUploadResponse response = migrationService.upload(migrationId, entity, file.getOriginalFilename(), content, sheet);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(response));
    }

    @GetMapping("/{migrationId}/files/{fileId}")
    public ResponseEntity<ApiResult<FileDetailResponse>> getFile(@PathVariable Long migrationId, @PathVariable Long fileId) {
        return ResponseEntity.ok(ApiResult.success(migrationService.getFile(migrationId, fileId)));
    }

    /** 엑셀 수식에서 뽑은 열별 규칙 (대표 수식, 값 직접 입력·빈 칸·다른 수식 행, 다른 시트와 비교) */
    @GetMapping("/{migrationId}/files/{fileId}/formulas")
    public ResponseEntity<ApiResult<FormulaRulesResponse>> formulas(@PathVariable Long migrationId, @PathVariable Long fileId) {
        return ResponseEntity.ok(ApiResult.success(migrationService.formulas(migrationId, fileId)));
    }

    /** 수식 규칙을 사람 말로 정리 (LLM, 셀 값은 보내지 않음). 한 번 만들면 저장해 두고 refresh=true일 때만 다시 만든다 */
    @GetMapping("/{migrationId}/files/{fileId}/rules/summary")
    public ResponseEntity<ApiResult<RuleSummaryResponse>> ruleSummary(@PathVariable Long migrationId, @PathVariable Long fileId,
                                                                      @RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(ApiResult.success(migrationService.ruleSummary(migrationId, fileId, refresh)));
    }

    @PutMapping("/{migrationId}/files/{fileId}/mappings")
    public ResponseEntity<ApiResult<MappingUpdateResponse>> updateMappings(@PathVariable Long migrationId,
                                                                           @PathVariable Long fileId,
                                                                           @Valid @RequestBody MappingUpdateRequest request) {
        return ResponseEntity.ok(ApiResult.success(migrationService.updateMappings(migrationId, fileId, request)));
    }

    /** 자동 정리 일괄 적용. 본문을 비우면 작업 전체, fileId·code·field로 묶음 하나만 적용 */
    @PostMapping("/{migrationId}/fixes")
    public ResponseEntity<ApiResult<FixApplyResponse>> applyFixes(@PathVariable Long migrationId,
                                                                  @RequestBody(required = false) FixApplyRequest request) {
        FixApplyRequest r = request == null ? FixApplyRequest.all() : request;
        return ResponseEntity.ok(ApiResult.success(migrationService.applyFixes(migrationId, r)));
    }

    /** 화면에서 행 하나 직접 수정 (원본 컬럼명 → 새 값) */
    @PatchMapping("/{migrationId}/files/{fileId}/rows/{rowNo}")
    public ResponseEntity<ApiResult<RowUpdateResponse>> updateRow(@PathVariable Long migrationId,
                                                                  @PathVariable Long fileId,
                                                                  @PathVariable int rowNo,
                                                                  @Valid @RequestBody RowUpdateRequest request) {
        return ResponseEntity.ok(ApiResult.success(migrationService.updateRow(migrationId, fileId, rowNo, request)));
    }

    /**
     * 적재. dryRun=true면 보낼 순서·건수·정리된 값 예시만 보여주고 실제로 보내지 않는다.
     * 본문의 fileIds를 비우면 적재 가능한 파일 전체를 entity 순서대로 넣는다.
     */
    @PostMapping("/{migrationId}/loads")
    public ResponseEntity<ApiResult<?>> load(@PathVariable Long migrationId,
                                             @RequestParam(defaultValue = "false") boolean dryRun,
                                             @RequestBody(required = false) LoadRequest request) {
        List<Long> fileIds = request == null ? null : request.fileIds();
        if (dryRun) {
            return ResponseEntity.ok(ApiResult.success(loadService.preview(migrationId, fileIds)));
        }
        return ResponseEntity.ok(ApiResult.success(loadService.load(migrationId, fileIds)));
    }

    /** 적재 묶음 되돌리기. 나중 순서의 묶음이 적재돼 있으면 409 */
    @DeleteMapping("/{migrationId}/batches/{batchId}")
    public ResponseEntity<ApiResult<LoadResponse>> rollback(@PathVariable Long migrationId, @PathVariable String batchId) {
        return ResponseEntity.ok(ApiResult.success(loadService.rollback(migrationId, batchId)));
    }

    /** 사원 파일에서 뽑은 기준정보(직위·직급·직책·직무)를 대상과 비교: 새로 만들 것 / 이미 있는 것 / 적재가 막히는 것 */
    @GetMapping("/{migrationId}/references")
    public ResponseEntity<ApiResult<ReferencePlanResponse>> references(@PathVariable Long migrationId) {
        return ResponseEntity.ok(ApiResult.success(loadService.references(migrationId)));
    }

    /** 대사: 적재된 묶음별 원본 행 수 vs 대상 서비스에 들어간 수 */
    @GetMapping("/{migrationId}/reconciliation")
    public ResponseEntity<ApiResult<List<ReconciliationItem>>> reconcile(@PathVariable Long migrationId) {
        return ResponseEntity.ok(ApiResult.success(loadService.reconcile(migrationId)));
    }

    /** 원인별 묶음(groups) + 행 단위 목록(content). fileId·severity 없으면 전체 */
    @GetMapping("/{migrationId}/issues")
    public ResponseEntity<ApiResult<IssueListResponse>> issues(@PathVariable Long migrationId,
                                                               @RequestParam(required = false) Long fileId,
                                                               @RequestParam(required = false) Severity severity,
                                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                                               @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return ResponseEntity.ok(ApiResult.success(migrationService.issues(migrationId, fileId, severity, page, size)));
    }
}
