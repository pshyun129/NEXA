package com.ssafy.user_service.domain.migration.service;

import com.ssafy.user_service.domain.migration.dto.MigrationBatchSummaryResponse;
import com.ssafy.user_service.domain.migration.dto.MigrationReferencesResponse;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertResponse;

/** migration-service 의 인사 데이터 이관 대상 (기준정보 → 조직 → 사원 순으로 적재, 되돌리기는 역순) */
public interface HrMigrationService {

    /** 한 요청은 하나의 트랜잭션: 한 행이라도 실패하면 이 요청분은 모두 취소된다 */
    /** 직위·직급·직책·직무 코드. 이미 있는 값은 바꾸지 않는다 */
    MigrationUpsertResponse upsertReferences(String batchId, MigrationUpsertRequest request);

    MigrationUpsertResponse upsertDepartments(String batchId, MigrationUpsertRequest request);

    MigrationUpsertResponse upsertEmployees(String batchId, MigrationUpsertRequest request);

    /** batch 되돌리기: 만든 행은 삭제, 수정한 행은 수정 전 값으로 복원. 없는 batch 는 아무것도 하지 않는다 */
    void rollback(String batchId);

    MigrationBatchSummaryResponse summarize(String batchId);

    /** 지금 있는 기준정보. migration 이 파일의 값과 비교해 새로 만들 것·이미 있는 것을 보여준다 */
    MigrationReferencesResponse references();
}
