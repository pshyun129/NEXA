package com.ssafy.user_service.domain.migration.controller;

import com.ssafy.user_service.domain.migration.dto.MigrationBatchSummaryResponse;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertResponse;
import com.ssafy.user_service.domain.migration.service.HrMigrationService;
import com.ssafy.user_service.global.dto.ApiResult;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import com.ssafy.user_service.global.security.SecurityConstants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * migration-service 전용 내부 API (토큰 없음, gateway 미노출). 형식은 migration HrTargetLoader 와 맞춘 계약이다.
 * POST   /{orgs|employees}/upsert?batchId=   → {created, updated}
 * DELETE /batches/{batchId}                  → 되돌리기 (data 없음)
 * GET    /batches/{batchId}/summary          → {count}
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(SecurityConstants.INTERNAL_MIGRATION_PATH)
public class InternalHrMigrationController {

    private static final int MAX_BATCH_ID_LENGTH = 100;   // migration_batch_row.batch_id

    private final HrMigrationService hrMigrationService;

    @PostMapping("/{entity}/upsert")
    public ResponseEntity<ApiResult<MigrationUpsertResponse>> upsert(
            @PathVariable String entity,
            @RequestParam String batchId,
            @Valid @RequestBody MigrationUpsertRequest request) {
        checkBatchId(batchId);
        MigrationUpsertResponse result = switch (entity) {
            case "orgs" -> hrMigrationService.upsertDepartments(batchId, request);
            case "employees" -> hrMigrationService.upsertEmployees(batchId, request);
            default -> throw new BusinessException(ErrorCode.NOT_FOUND, "지원하지 않는 이관 대상입니다: " + entity);
        };
        return ResponseEntity.ok(ApiResult.success(result));
    }

    @DeleteMapping("/batches/{batchId}")
    public ResponseEntity<ApiResult<Void>> rollback(@PathVariable String batchId) {
        checkBatchId(batchId);
        hrMigrationService.rollback(batchId);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    @GetMapping("/batches/{batchId}/summary")
    public ResponseEntity<ApiResult<MigrationBatchSummaryResponse>> summarize(
            @PathVariable String batchId) {
        checkBatchId(batchId);
        return ResponseEntity.ok(ApiResult.success(hrMigrationService.summarize(batchId)));
    }

    private static void checkBatchId(String batchId) {
        if (batchId.isBlank() || batchId.length() > MAX_BATCH_ID_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "batchId 는 1~" + MAX_BATCH_ID_LENGTH + "자여야 합니다.");
        }
    }
}
