package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeValueCheckerTest {

    @Test
    void 드문_값이_흔한_값과_자모_하나만_다르면_오타로_의심한다() {
        List<ParsedTable.Row> rows = rows("과장", 10, "대리", 8, "차장", 5, "부장", 2, "과정", 1, "데리", 1);

        Map<String, CodeValueChecker.Suspect> s = CodeValueChecker.suspects(rows, "직위");

        assertThat(s).containsOnlyKeys("과정", "데리");
        assertThat(s.get("과정").likely()).isEqualTo("과장");
        assertThat(s.get("데리").likely()).isEqualTo("대리");
    }

    @Test
    void 실제로_다른_직위와_한글이_없는_코드는_오타로_보지_않는다() {
        List<ParsedTable.Row> rows = rows("차장", 20, "부장", 1, "G1", 30, "G2", 1);

        assertThat(CodeValueChecker.suspects(rows, "직위")).isEmpty();
        assertThat(CodeValueChecker.editDistance(CodeValueChecker.jamo("부장"), CodeValueChecker.jamo("차장"))).isEqualTo(2);
    }

    private static List<ParsedTable.Row> rows(Object... valueAndCount) {
        List<ParsedTable.Row> rows = new ArrayList<>();
        for (int i = 0; i < valueAndCount.length; i += 2) {
            for (int n = 0; n < (Integer) valueAndCount[i + 1]; n++) {
                rows.add(new ParsedTable.Row(rows.size() + 2, Map.of("직위", (String) valueAndCount[i])));
            }
        }
        return rows;
    }
}
