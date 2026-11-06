package com.ssafy.user_service.domain.migration.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * migration_batch_row 기록과 되돌리기용 스냅샷·복원 (PostgreSQL 전용 JDBC).
 * 스냅샷은 row_to_json 으로 행 전체를 저장하고, 복원은 json_populate_record 로 PK 외 모든 컬럼을 되돌린다.
 * 컬럼을 하나씩 나열하지 않으므로 나중에 컬럼이 늘어도 복원 범위가 따라간다.
 */
@Repository
@RequiredArgsConstructor
public class MigrationBatchRowRepository {

    public enum Target {
        DEPARTMENT("department", "dept_id"),
        EMPLOYEE("employee", "emp_id"),
        POSITION("position", "position_id"),
        GRADE("grade", "grade_id"),
        DUTY("duty", "duty_id"),
        JOB_CODE("job_code", "job_code_id");

        private final String table;
        private final String pk;

        Target(String table, String pk) {
            this.table = table;
            this.pk = pk;
        }

        /** position 처럼 PostgreSQL 예약어인 테이블 이름이 있어 SQL 에는 따옴표로 감싼다 */
        private String quoted() {
            return "\"" + table + "\"";
        }
    }

    public enum Action { CREATED, UPDATED }

    public record Entry(long id, Target target, long targetId, Action action, String beforeJson) {
    }

    private final JdbcTemplate jdbc;
    // 테이블별 복원 SQL (information_schema 로 한 번 만든다)
    private final Map<Target, String> restoreSql = new ConcurrentHashMap<>();

    public boolean isRecorded(String batchId, Target target, long targetId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM migration_batch_row WHERE batch_id = ? AND entity = ? AND target_id = ?",
                Integer.class, batchId, target.name(), targetId);
        return n != null && n > 0;
    }

    /** rowNo 가 null 이면 원본 행이 아닌 부수 생성 (예: 직무 경로를 만들며 생긴 직군·직종) */
    public void recordCreated(String batchId, Target target, long targetId, Integer rowNo) {
        jdbc.update("""
                INSERT INTO migration_batch_row (batch_id, entity, target_id, action, row_no)
                VALUES (?, ?, ?, 'CREATED', ?)""", batchId, target.name(), targetId, rowNo);
    }

    /**
     * 수정 직전에 호출한다. 이 batch 에서 처음 건드리는 행이면 현재 값을 스냅샷으로 남긴다.
     * 이미 기록이 있으면(같은 batch 에서 생성했거나 앞서 수정함) 첫 스냅샷을 유지하고,
     * 부수 변경(rowNo null)으로 먼저 기록된 행이 원본 행으로 다시 오면 행 번호만 채운다.
     */
    public void recordUpdateIfAbsent(String batchId, Target target, long targetId, Integer rowNo) {
        if (isRecorded(batchId, target, targetId)) {
            if (rowNo != null) {
                jdbc.update("""
                        UPDATE migration_batch_row SET row_no = ?
                        WHERE batch_id = ? AND entity = ? AND target_id = ? AND row_no IS NULL""",
                        rowNo, batchId, target.name(), targetId);
            }
            return;
        }
        String before = jdbc.queryForObject(
                "SELECT row_to_json(t)::text FROM " + target.quoted() + " t WHERE " + target.pk + " = ?",
                String.class, targetId);
        jdbc.update("""
                INSERT INTO migration_batch_row (batch_id, entity, target_id, action, before_json, row_no)
                VALUES (?, ?, ?, 'UPDATED', ?, ?)""", batchId, target.name(), targetId, before, rowNo);
    }

    /** 원본 행으로 적재된 수 (부수 변경 제외) */
    public long countRows(String batchId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM migration_batch_row WHERE batch_id = ? AND row_no IS NOT NULL",
                Long.class, batchId);
        return n == null ? 0 : n;
    }

    /** 최근 기록부터 */
    public List<Entry> entries(String batchId) {
        return jdbc.query("""
                        SELECT id, entity, target_id, action, before_json FROM migration_batch_row
                        WHERE batch_id = ? ORDER BY id DESC""",
                (rs, i) -> new Entry(rs.getLong("id"), Target.valueOf(rs.getString("entity")),
                        rs.getLong("target_id"), Action.valueOf(rs.getString("action")), rs.getString("before_json")),
                batchId);
    }

    /** 이 batch 가 건드린 행을 그 뒤에 다시 건드린 다른 batch 들 (먼저 되돌려야 하는 batch) */
    public List<String> laterBatchesTouchingSameRows(String batchId) {
        return jdbc.queryForList("""
                SELECT DISTINCT later.batch_id FROM migration_batch_row mine
                JOIN migration_batch_row later
                  ON later.entity = mine.entity AND later.target_id = mine.target_id
                 AND later.id > mine.id AND later.batch_id <> mine.batch_id
                WHERE mine.batch_id = ?
                ORDER BY later.batch_id""", String.class, batchId);
    }

    public void restore(Entry entry) {
        jdbc.update(restoreSql.computeIfAbsent(entry.target(), this::buildRestoreSql),
                entry.beforeJson(), entry.targetId());
    }

    /** 이 batch 로 만든 부서끼리의 상위·부서장 참조를 먼저 끊는다 (삭제 순서와 무관하게 FK 가 걸리지 않게) */
    public void detachDepartments(List<Long> deptIds) {
        if (deptIds.isEmpty()) {
            return;
        }
        String in = deptIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("UPDATE department SET parent_dept_id = NULL, head_emp_id = NULL WHERE dept_id IN (" + in + ")");
    }

    public void delete(Target target, long targetId) {
        jdbc.update("DELETE FROM " + target.quoted() + " WHERE " + target.pk + " = ?", targetId);
    }

    public void deleteBatch(String batchId) {
        jdbc.update("DELETE FROM migration_batch_row WHERE batch_id = ?", batchId);
    }

    private String buildRestoreSql(Target target) {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name <> ?
                ORDER BY ordinal_position""", String.class, target.table, target.pk);
        String cols = columns.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", "));
        return "UPDATE " + target.quoted() + " SET (" + cols + ") = (SELECT " + cols
                + " FROM json_populate_record(NULL::" + target.quoted() + ", CAST(? AS json))) WHERE " + target.pk + " = ?";
    }
}
