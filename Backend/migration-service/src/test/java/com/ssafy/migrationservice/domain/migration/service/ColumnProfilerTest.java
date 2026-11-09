package com.ssafy.migrationservice.domain.migration.service;

import com.ssafy.migrationservice.domain.migration.dto.ColumnProfile;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnProfilerTest {

    @Test
    void 날짜_표기가_섞여_있으면_종류를_모두_보여준다() {
        List<ParsedTable.Row> rows = List.of(
                row(1, "2024-01-02", "1,000"),
                row(2, "2024.1.2", "2000"),
                row(3, null, null),
                row(4, "20240103", "3000"));

        List<ColumnProfile> profiles = ColumnProfiler.profile(List.of("입사일", "금액"), rows);

        ColumnProfile date = profiles.get(0);
        assertThat(date.detectedType()).isEqualTo("DATE");
        assertThat(date.dateFormats()).containsExactly("YYYY-MM-DD", "YYYY.MM.DD", "YYYYMMDD");
        assertThat(date.nullRate()).isEqualTo(0.25);
        assertThat(date.distinctCount()).isEqualTo(3);

        assertThat(profiles.get(1).detectedType()).isEqualTo("NUMBER");
    }

    @Test
    void 날짜가_아닌_값이_하나라도_있으면_문자로_본다() {
        List<ColumnProfile> profiles = ColumnProfiler.profile(List.of("입사일"),
                List.of(row(1, "2024-01-02", null), row(2, "미정", null)));

        assertThat(profiles.get(0).detectedType()).isEqualTo("STRING");
        assertThat(profiles.get(0).dateFormats()).isEmpty();
    }

    private static ParsedTable.Row row(int no, String date, String amount) {
        Map<String, String> v = new HashMap<>();
        v.put("입사일", date);
        v.put("금액", amount);
        return new ParsedTable.Row(no, v);
    }
}
