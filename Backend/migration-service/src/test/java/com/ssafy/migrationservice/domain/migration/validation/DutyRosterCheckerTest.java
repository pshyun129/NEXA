package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.formula.Countif;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DutyRosterCheckerTest {

    private static final String LEGEND = "작성: 수간호사 (인)   D 07:00~15:30 / E 15:00~23:30 / N 23:00~07:30";

    @Test
    void 범례에서_근무_코드_시각을_읽는다() {
        Map<String, DutyRosterChecker.Shift> legend = DutyRosterChecker.legend(LEGEND);

        assertThat(legend).containsOnlyKeys("D", "E", "N");
        assertThat(legend.get("N").endFromStartDay()).isEqualTo((24 + 7) * 60 + 30);
    }

    @Test
    void 근무가_겹치거나_휴식이_11시간_미만이거나_7일_넘게_연속이면_알린다() {
        // 1행: 3일 N 다음 4일 D (겹침), 2행: 6일 E 다음 7일 D (휴식 7시간 30분), 3행: 1~8일 쉬지 않음, 4행: 정상
        List<ParsedTable.Row> rows = List.of(
                row(1, "O", "O", "N", "D", "O", "O", "O", "O"),
                row(2, "O", "O", "O", "O", "O", "E", "D", "O"),
                row(3, "D", "D", "D", "E", "E", "E", "N", "N"),
                row(4, "D", "D", "O", "E", "E", "O", "N", "N"));

        List<ValidationIssue> issues = DutyRosterChecker.check(file(LEGEND), rows);

        assertThat(issues).extracting(i -> i.rowNo() + ":" + i.code() + ":" + i.sourceColumn())
                .containsExactlyInAnyOrder("1:SHIFT_OVERLAP:4(금)", "2:REST_INTERVAL_SHORT:7(월)", "3:CONSECUTIVE_WORK_DAYS:7(월)");
    }

    @Test
    void 범례가_없으면_근무표_검사를_하지_않는다() {
        assertThat(DutyRosterChecker.check(file(null), List.of(row(1, "N", "D", "D", "D", "D", "D", "D", "D")))).isEmpty();
    }

    @Test
    void 같은_행_COUNTIF는_공백이_붙은_칸까지_다시_센다() {
        List<String> headers = List.of("성명", "1", "2", "3", "N");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("성명", "홍길동");
        values.put("1", "N");     // 원본은 'N ' (읽을 때 공백을 지움)
        values.put("2", "N");
        values.put("3", "O");
        values.put("N", "1");     // 엑셀은 'N '을 세지 않아 1
        ParsedTable.Row row = new ParsedTable.Row(5, values, Map.of("N", "COUNTIF($B5:$D5,\"N\")"), Map.of());

        assertThat(Countif.recount(headers, row, "N")).contains(BigDecimal.valueOf(2));
        assertThat(Countif.recount(headers, row, "성명")).isEmpty();
    }

    private static MigrationFile file(String preamble) {
        List<String> headers = new ArrayList<>(List.of("사번", "성명"));
        String[] week = {"화", "수", "목", "금", "토", "일", "월"};
        for (int d = 1; d <= 31; d++) {
            headers.add(d + "(" + week[(d - 1) % 7] + ")");
        }
        return MigrationFile.builder().migrationId(1L).entity(MigrationEntity.REFERENCE).fileName("근무표_2026-12.xlsx")
                .headers(headers.toArray(String[]::new)).sheetName("2병동").preamble(preamble).build();
    }

    /** 1일부터 적은 코드, 나머지 날은 O */
    private static ParsedTable.Row row(int rowNo, String... codes) {
        String[] headers = file(null).getHeaders();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("사번", "200" + rowNo);
        values.put("성명", "간호사" + rowNo);
        for (int d = 1; d <= 31; d++) {
            values.put(headers[d + 1], d <= codes.length ? codes[d - 1] : "O");
        }
        return new ParsedTable.Row(rowNo, values);
    }
}
