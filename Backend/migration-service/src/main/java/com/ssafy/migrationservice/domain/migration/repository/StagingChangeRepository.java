package com.ssafy.migrationservice.domain.migration.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 임시 테이블 값 수정 + 변경 이력 기록.
 * 자동 정리는 FIX 이슈의 제안값을 SQL로 한 번에 반영하고, 직접 수정은 셀 단위로 반영한다.
 */
@Repository
@RequiredArgsConstructor
public class StagingChangeRepository {

    // 반영할 FIX 이슈. fileId·code·field가 null이면 조건 없음
    private static final String FIX_ISSUES = """
            FROM validation_issue i
            WHERE i.migration_id = ? AND i.severity = 'FIX' AND i.suggestion IS NOT NULL AND i.row_no IS NOT NULL
              AND (CAST(? AS BIGINT) IS NULL OR i.file_id = ?)
              AND (CAST(? AS TEXT) IS NULL OR i.code = ?)
              AND (CAST(? AS TEXT) IS NULL OR i.field = ?)""";

    private final JdbcTemplate jdbcTemplate;

    /** 반영 대상 파일 (재검증용). applyFixes 전에 호출 */
    public List<Long> filesWithFixes(Long migrationId, Long fileId, String code, String field) {
        return jdbcTemplate.queryForList("SELECT DISTINCT i.file_id " + FIX_ISSUES, Long.class,
                fixParams(migrationId, fileId, code, field));
    }

    /**
     * FIX 이슈의 suggestion을 원본 값 대신 넣는다.
     * 같은 행에 FIX가 여러 개면 jsonb로 합쳐 한 번에 반영한다 (UPDATE ... FROM은 대상 행당 한 건만 쓰기 때문).
     * @return 바뀐 셀 수
     */
    public int applyFixes(Long migrationId, Long fileId, String code, String field) {
        Object[] p = fixParams(migrationId, fileId, code, field);
        int changed = jdbcTemplate.update("""
                INSERT INTO staging_change (file_id, row_no, source_column, old_value, new_value, change_type, changed_at)
                SELECT i.file_id, i.row_no, i.source_column, i.value, i.suggestion, 'AUTO_FIX', now()
                %s""".formatted(FIX_ISSUES), p);
        if (changed > 0) {
            jdbcTemplate.update("""
                    UPDATE staging_row s
                    SET data = s.data || c.changes
                    FROM (SELECT i.file_id, i.row_no, jsonb_object_agg(i.source_column, i.suggestion) AS changes
                          %s
                          GROUP BY i.file_id, i.row_no) c
                    WHERE s.file_id = c.file_id AND s.row_no = c.row_no""".formatted(FIX_ISSUES), p);
        }
        return changed;
    }

    /** 셀 하나를 사람이 고친다. newValue가 null이면 빈 값으로. 행이 없으면 false */
    public boolean updateCell(Long fileId, int rowNo, String column, String newValue) {
        List<Optional<String>> old = jdbcTemplate.query(
                "SELECT data ->> ? AS v FROM staging_row WHERE file_id = ? AND row_no = ?",
                (rs, n) -> Optional.ofNullable(rs.getString("v")), column, fileId, rowNo);
        if (old.isEmpty()) {
            return false;
        }
        jdbcTemplate.update("""
                        UPDATE staging_row
                        SET data = jsonb_set(data, ARRAY[CAST(? AS TEXT)], COALESCE(to_jsonb(CAST(? AS TEXT)), 'null'::jsonb))
                        WHERE file_id = ? AND row_no = ?""",
                column, newValue, fileId, rowNo);
        jdbcTemplate.update("""
                        INSERT INTO staging_change (file_id, row_no, source_column, old_value, new_value, change_type, changed_at)
                        VALUES (?, ?, ?, ?, ?, 'MANUAL', now())""",
                fileId, rowNo, column, old.get(0).orElse(null), newValue);
        return true;
    }

    private static Object[] fixParams(Long migrationId, Long fileId, String code, String field) {
        return new Object[]{migrationId, fileId, fileId, code, code, field, field};
    }
}
