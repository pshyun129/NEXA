package com.ssafy.migrationservice.domain.migration.validation.formula;

import com.ssafy.migrationservice.domain.migration.dto.FormulaRulesResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSummaryServiceTest {

    @Test
    void LLM에는_열_이름과_대표_수식과_예외_개수만_보낸다() {
        RuleSummaryService service = new RuleSummaryService(null, null, null, null, JsonMapper.builder().build());
        var np = new FormulaRulesResponse.Column("국민연금", "=ROUNDDOWN({과세대상}*0.045,-1)", true, 110, 108,
                List.of(12, 40), List.of(), Map.of(55, "={과세대상}*0.0475"), "DIFFERENT", "=ROUNDDOWN({과세대상}*설정!$B$4,-1)",
                List.of("2026-08"));

        String input = service.input("2026-07", List.of(np));

        assertThat(input).contains("2026-07", "국민연금", "=ROUNDDOWN({과세대상}*0.045,-1)", "\"hardcodedRows\":2",
                "\"otherFormulaRows\":1", "설정!$B$4");
        assertThat(input).doesNotContain("[12,40]", "55");     // 행 번호·값은 보내지 않음
    }
}
