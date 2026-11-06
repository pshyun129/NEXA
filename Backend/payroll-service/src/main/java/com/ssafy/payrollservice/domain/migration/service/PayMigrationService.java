package com.ssafy.payrollservice.domain.migration.service;

import com.ssafy.payrollservice.domain.migration.dto.MigrationBatchSummaryResponse;
import com.ssafy.payrollservice.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.payrollservice.domain.migration.dto.MigrationUpsertResponse;

/** migration-service 의 급여 이관 대상 (직급별 기본급 → 지난 급여명세서 순으로 적재, 되돌리기는 역순) */
public interface PayMigrationService {

    /** 직급별 기본급 {gradeId, baseAmount}. 이미 있는 직급은 바꾸지 않는다. 한 요청은 하나의 트랜잭션 */
    MigrationUpsertResponse upsertGrades(String batchId, MigrationUpsertRequest request);

    /** 지난 급여명세서. 같은 사원·연월 명세서가 이미 있으면 그 행 오류로 이 요청분은 모두 취소된다 */
    MigrationUpsertResponse upsertPayslips(String batchId, MigrationUpsertRequest request);

    /** 이 batch 로 만든 기본급·명세서 삭제. 없는 batch 는 아무것도 하지 않는다 */
    void rollback(String batchId);

    MigrationBatchSummaryResponse summarize(String batchId);
}
