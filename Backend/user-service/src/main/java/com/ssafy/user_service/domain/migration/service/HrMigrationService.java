package com.ssafy.user_service.domain.migration.service;

import com.ssafy.user_service.domain.migration.dto.MigrationBatchSummaryResponse;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertResponse;

/** migration-service 의 인사 데이터 이관 대상 (조직 → 사원 순으로 적재, 되돌리기는 역순) */
public interface HrMigrationService {

    /** 한 요청은 하나의 트랜잭션: 한 행이라도 실패하면 이 요청분은 모두 취소된다 */
    MigrationUpsertResponse upsertDepartments(String batchId, MigrationUpsertRequest request);

    MigrationUpsertResponse upsertEmployees(String batchId, MigrationUpsertRequest request);

    /** batch 되돌리기: 만든 행은 삭제, 수정한 행은 수정 전 값으로 복원. 없는 batch 는 아무것도 하지 않는다 */
    void rollback(String batchId);

    MigrationBatchSummaryResponse summarize(String batchId);
}
