package com.ssafy.payrollservice.domain.migration.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** migration_batch_row 기록 (summary·되돌리기용). 이관은 있는 행을 바꾸지 않아 복원할 값은 없다 */
@Repository
@RequiredArgsConstructor
public class MigrationBatchRowRepository {

    public enum Target { PAY_GRADE, PAYSLIP }

    public enum Action { CREATED, EXISTING }

    public record Entry(Target target, long targetId, Action action) {
    }

    private final JdbcTemplate jdbc;

    /** 같은 batch 에서 같은 행을 또 보내면 한 줄만 남긴다 */
    public void record(String batchId, Target target, long targetId, Action action, int rowNo) {
        jdbc.update("""
                INSERT INTO migration_batch_row (batch_id, entity, target_id, action, row_no)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (batch_id, entity, target_id) DO NOTHING""",
                batchId, target.name(), targetId, action.name(), rowNo);
    }

    public long countRows(String batchId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM migration_batch_row WHERE batch_id = ?", Long.class, batchId);
        return n == null ? 0 : n;
    }

    /** 최근 기록부터 */
    public List<Entry> entries(String batchId) {
        return jdbc.query("SELECT entity, target_id, action FROM migration_batch_row WHERE batch_id = ? ORDER BY id DESC",
                (rs, i) -> new Entry(Target.valueOf(rs.getString("entity")), rs.getLong("target_id"),
                        Action.valueOf(rs.getString("action"))),
                batchId);
    }

    /** 만든 명세서와 그 항목 삭제 */
    public void deletePayslip(long payslipId) {
        jdbc.update("DELETE FROM payslip_item WHERE payslip_id = ?", payslipId);
        jdbc.update("DELETE FROM payslip WHERE payslip_id = ?", payslipId);
    }

    public void deletePayGrade(long gradeId) {
        jdbc.update("DELETE FROM pay_grade WHERE grade_id = ?", gradeId);
    }

    public void deleteBatch(String batchId) {
        jdbc.update("DELETE FROM migration_batch_row WHERE batch_id = ?", batchId);
    }
}
