package com.ssafy.payrollservice.domain.migration.controller;

import com.ssafy.payrollservice.domain.migration.dto.MigrationBatchSummaryResponse;
import com.ssafy.payrollservice.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.payrollservice.domain.migration.dto.MigrationUpsertResponse;
import com.ssafy.payrollservice.domain.migration.service.PayMigrationService;
import com.ssafy.payrollservice.global.dto.ApiResult;
import com.ssafy.payrollservice.global.exception.BusinessException;
import com.ssafy.payrollservice.global.exception.ErrorCode;
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
 * migration-service 전용 내부 API (토큰 없음, gateway 미노출). user-service 이관 내부 API와 같은 모양이다.
 * POST   /{grades|payslips}/upsert?batchId=   → {created, updated}
 * DELETE /batches/{batchId}                   → 되돌리기 (data 없음)
 * GET    /batches/{batchId}/summary           → {count}
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(InternalPayMigrationController.PATH)
public class InternalPayMigrationController {

    public static final String PATH = "/internal/v1/pay/migration";
    private static final int MAX_BATCH_ID_LENGTH = 100;   // migration_batch_row.batch_id

    private final PayMigrationService payMigrationService;

    @PostMapping("/{entity}/upsert")
    public ResponseEntity<ApiResult<MigrationUpsertResponse>> upsert(
            @PathVariable String entity,
            @RequestParam String batchId,
            @Valid @RequestBody MigrationUpsertRequest request) {
        checkBatchId(batchId);
        MigrationUpsertResponse result = switch (entity) {
            case "grades" -> payMigrationService.upsertGrades(batchId, request);
            case "payslips" -> payMigrationService.upsertPayslips(batchId, request);
            default -> throw new BusinessException(ErrorCode.NOT_FOUND, "지원하지 않는 이관 대상입니다: " + entity);
        };
        return ResponseEntity.ok(ApiResult.success(result));
    }

    @DeleteMapping("/batches/{batchId}")
    public ResponseEntity<ApiResult<Void>> rollback(@PathVariable String batchId) {
        checkBatchId(batchId);
        payMigrationService.rollback(batchId);
        return ResponseEntity.ok(ApiResult.success(null));
    }

    @GetMapping("/batches/{batchId}/summary")
    public ResponseEntity<ApiResult<MigrationBatchSummaryResponse>> summarize(@PathVariable String batchId) {
        checkBatchId(batchId);
        return ResponseEntity.ok(ApiResult.success(payMigrationService.summarize(batchId)));
    }

    private static void checkBatchId(String batchId) {
        if (batchId.isBlank() || batchId.length() > MAX_BATCH_ID_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "batchId 는 1~" + MAX_BATCH_ID_LENGTH + "자여야 합니다.");
        }
    }
}
