package com.ssafy.migrationservice.domain.migration.validation.issue;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 검증 결과는 건수가 많아 JDBC 배치로 저장하고, 원인별 묶음은 SQL GROUP BY로 만든다. */
@Repository
@RequiredArgsConstructor
public class IssueRepository {

    private static final int BATCH_SIZE = 1000;
    private static final int SAMPLE_SIZE = 5;

    // fileId·severity가 null이면 조건을 걸지 않는다
    private static final String FILTER = """
            WHERE migration_id = ? AND (CAST(? AS BIGINT) IS NULL OR file_id = ?)
              AND (CAST(? AS TEXT) IS NULL OR severity = ?)""";

    private final JdbcTemplate jdbcTemplate;

    public void replaceForFile(Long migrationId, Long fileId, List<ValidationIssue> issues) {
        jdbcTemplate.update("DELETE FROM validation_issue WHERE file_id = ?", fileId);
        jdbcTemplate.batchUpdate("""
                        INSERT INTO validation_issue
                            (migration_id, file_id, row_no, field, source_column, severity, code, message, value, suggestion)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                issues, BATCH_SIZE,
                (ps, i) -> {
                    ps.setLong(1, migrationId);
                    ps.setLong(2, fileId);
                    if (i.rowNo() == null) {
                        ps.setNull(3, Types.INTEGER);
                    } else {
                        ps.setInt(3, i.rowNo());
                    }
                    ps.setString(4, i.field());
                    ps.setString(5, i.sourceColumn());
                    ps.setString(6, i.severity().name());
                    ps.setString(7, i.code().name());
                    ps.setString(8, i.message());
                    ps.setString(9, i.value());
                    ps.setString(10, i.suggestion());
                });
    }

    public Map<Severity, Long> totals(Long migrationId, Long fileId) {
        Map<Severity, Long> totals = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            totals.put(s, 0L);
        }
        jdbcTemplate.query("SELECT severity, COUNT(*) AS cnt FROM validation_issue " + FILTER + " GROUP BY severity",
                rs -> {
                    totals.put(Severity.valueOf(rs.getString("severity")), rs.getLong("cnt"));
                },
                params(migrationId, fileId, null));
        return totals;
    }

    /** 같은 파일·같은 원인(code, field)끼리 묶어 건수와 예시 행·값을 보여준다 */
    public List<IssueGroup> groups(Long migrationId, Long fileId, Severity severity) {
        String sql = """
                SELECT file_id, code, field, source_column, severity, COUNT(*) AS cnt,
                       MIN(message) AS message, COUNT(DISTINCT message) AS message_kinds,
                       (ARRAY_AGG(row_no ORDER BY row_no) FILTER (WHERE row_no IS NOT NULL))[1:%d] AS sample_rows,
                       (ARRAY_AGG(DISTINCT value) FILTER (WHERE value IS NOT NULL))[1:%d] AS sample_values,
                       MIN(suggestion) AS sample_suggestion, COUNT(DISTINCT suggestion) AS suggestion_kinds
                FROM validation_issue
                %s
                GROUP BY file_id, code, field, source_column, severity
                ORDER BY CASE severity WHEN 'BLOCK' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END, cnt DESC, code"""
                .formatted(SAMPLE_SIZE, SAMPLE_SIZE, FILTER);
        return jdbcTemplate.query(sql,
                (rs, n) -> new IssueGroup(
                        rs.getLong("file_id"),
                        IssueCode.valueOf(rs.getString("code")),
                        rs.getString("field"),
                        rs.getString("source_column"),
                        Severity.valueOf(rs.getString("severity")),
                        rs.getLong("cnt"),
                        groupMessage(rs.getString("code"), rs.getString("source_column"), rs.getString("message"), rs.getLong("message_kinds")),
                        intList(rs.getArray("sample_rows")),
                        stringList(rs.getArray("sample_values")),
                        // 바꿀 값이 여럿이면 묶음에는 하나만 보여 주지 않는다 (예: 계약 → 계약직, 정규 → 정규직)
                        rs.getLong("suggestion_kinds") > 1 ? null : rs.getString("sample_suggestion")),
                params(migrationId, fileId, severity));
    }

    /** 묶음 제목. 값마다 문장이 다른 허용 값 제안은 첫 행 문장 대신 묶음 전체를 말한다 */
    private static String groupMessage(String code, String column, String message, long kinds) {
        if (kinds > 1 && IssueCode.ENUM_VALUE_SUGGESTED.name().equals(code)) {
            return "'" + column + "'의 허용되지 않는 값을 허용 값으로 바꿉니다 (값마다 바꿀 값은 행 단위 표에서 확인)";
        }
        return message;
    }

    public List<ValidationIssue> page(Long migrationId, Long fileId, Severity severity, int page, int size) {
        Object[] p = params(migrationId, fileId, severity);
        Object[] withPaging = Arrays.copyOf(p, p.length + 2);
        withPaging[p.length] = size;
        withPaging[p.length + 1] = (long) page * size;
        return jdbcTemplate.query("""
                        SELECT file_id, row_no, field, source_column, severity, code, message, value, suggestion
                        FROM validation_issue
                        %s
                        ORDER BY file_id, row_no NULLS FIRST, issue_id
                        LIMIT ? OFFSET ?""".formatted(FILTER),
                (rs, n) -> new ValidationIssue(
                        rs.getLong("file_id"),
                        (Integer) rs.getObject("row_no"),
                        rs.getString("field"),
                        rs.getString("source_column"),
                        Severity.valueOf(rs.getString("severity")),
                        IssueCode.valueOf(rs.getString("code")),
                        rs.getString("message"),
                        rs.getString("value"),
                        rs.getString("suggestion")),
                withPaging);
    }

    public List<ValidationIssue> findByRow(Long fileId, int rowNo) {
        return jdbcTemplate.query("""
                        SELECT file_id, row_no, field, source_column, severity, code, message, value, suggestion
                        FROM validation_issue WHERE file_id = ? AND row_no = ? ORDER BY issue_id""",
                (rs, n) -> new ValidationIssue(
                        rs.getLong("file_id"),
                        (Integer) rs.getObject("row_no"),
                        rs.getString("field"),
                        rs.getString("source_column"),
                        Severity.valueOf(rs.getString("severity")),
                        IssueCode.valueOf(rs.getString("code")),
                        rs.getString("message"),
                        rs.getString("value"),
                        rs.getString("suggestion")),
                fileId, rowNo);
    }

    public long count(Long migrationId, Long fileId, Severity severity) {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM validation_issue " + FILTER,
                Long.class, params(migrationId, fileId, severity));
        return n == null ? 0 : n;
    }

    private static Object[] params(Long migrationId, Long fileId, Severity severity) {
        String sev = severity == null ? null : severity.name();
        return new Object[]{migrationId, fileId, fileId, sev, sev};
    }

    private static List<Integer> intList(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (Object o : (Object[]) a.getArray()) {
            out.add(((Number) o).intValue());
        }
        return out;
    }

    private static List<String> stringList(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
    }

    public record IssueGroup(Long fileId, IssueCode code, String field, String sourceColumn, Severity severity,
                             long count, String message, List<Integer> sampleRows, List<String> sampleValues,
                             String sampleSuggestion) {
    }
}
