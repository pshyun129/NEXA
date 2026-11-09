package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;

import java.util.List;

/**
 * 대상 서비스(인사·휴가·급여)에 데이터를 넣는 쪽.
 * 이관 서비스는 다른 서비스 DB에 직접 쓰지 않고, 각 서비스가 여는 이관용 내부 API만 호출한다.
 */
public interface TargetLoader {

    boolean supports(MigrationEntity entity);

    /** 기준 키로 있으면 수정, 없으면 생성. batchId로 묶어 두어야 되돌릴 수 있다 */
    LoadResult upsert(MigrationEntity entity, String batchId, List<LoadRow> rows);

    /** 해당 묶음으로 들어간 데이터를 전부 취소 */
    void rollback(MigrationEntity entity, String batchId);

    TargetSummary summarize(MigrationEntity entity, String batchId);
}
