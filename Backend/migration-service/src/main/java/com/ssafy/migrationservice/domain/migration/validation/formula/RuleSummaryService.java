package com.ssafy.migrationservice.domain.migration.validation.formula;

import com.ssafy.migrationservice.domain.migration.dto.FormulaRulesResponse;
import com.ssafy.migrationservice.domain.migration.dto.RuleSummaryResponse;
import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.repository.StagingRowRepository;
import com.ssafy.migrationservice.global.exception.BusinessException;
import com.ssafy.migrationservice.global.exception.ErrorCode;
import com.ssafy.migrationservice.global.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 엑셀 수식에서 뽑은 열별 규칙을 인사 담당자가 읽을 수 있는 말로 정리한다 (LLM).
 * 보내는 것은 열 이름과 대표 수식, 벗어난 행 개수뿐이고 셀 값은 보내지 않는다. 결과는 파일마다 저장해 다시 쓴다.
 */
@Service
@RequiredArgsConstructor
public class RuleSummaryService {

    private static final String INSTRUCTIONS = """
            너는 한국 회사의 급여·인사 엑셀을 새 HR 시스템으로 옮기는 담당자를 돕는다.
            rules는 엑셀 열마다 대표 수식이다. {열 이름}은 같은 행의 그 열, 시트!셀은 다른 시트(설정·요율표 등)의 값이다.
            이 회사가 무엇을 어떻게 계산하는지 한국어로 정리한다.
            - 지급 항목, 법정수당(연장·야간·휴일), 4대보험, 세금, 공제·실지급 순서로 묶고, 각 줄은 '항목: 계산 방법' 한 줄
            - 끝수 처리(ROUND·ROUNDDOWN·TRUNC 자리수)는 '10원 미만 버림'처럼 풀어서 적는다
            - 다른 시트 셀을 쓰면 '설정 시트 B9 값'처럼 적고, 그 값을 지어내지 않는다
            - 요율이나 배율이 셀 참조가 아니라 숫자로 박혀 있으면 그 숫자를 적고 '숫자로 입력됨'이라고 표시한다
            - exceptions가 있으면 마지막에 '확인할 점'으로 짧게 적는다 (값 직접 입력, 빈 칸, 다른 수식, 다른 시트와 다름)
            마크다운 목록으로 30줄 이내. 수식에 없는 내용은 추측하지 않는다.
            """;

    private final FormulaRuleService formulaRuleService;
    private final StagingRowRepository stagingRowRepository;
    private final RuleSummaryRepository repository;
    private final LlmClient llm;
    private final ObjectMapper objectMapper;

    /** LLM 호출(수 초~수십 초) 동안 DB 연결을 잡지 않도록 트랜잭션 없이, 저장만 repository 트랜잭션으로 */
    public RuleSummaryResponse summarize(MigrationFile file, boolean refresh) {
        if (!refresh) {
            var cached = repository.findById(file.getFileId());
            if (cached.isPresent()) {
                return RuleSummaryResponse.of(file, cached.get(), true);
            }
        }
        List<FormulaRulesResponse.Column> columns = formulaRuleService
                .inspect(file, stagingRowRepository.findByFileId(file.getFileId())).columns().stream()
                .filter(FormulaRulesResponse.Column::formulaColumn).toList();
        if (columns.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "수식 규칙이 있는 열이 없어 요약할 것이 없습니다");
        }
        if (!llm.enabled()) {
            throw new BusinessException(ErrorCode.LLM_UNAVAILABLE);
        }
        String summary;
        try {
            summary = llm.call(INSTRUCTIONS, input(file.getSheetName(), columns));
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.LLM_UNAVAILABLE, "규칙 요약을 만들지 못했습니다: " + e.getMessage());
        }
        RuleSummary saved = repository.save(new RuleSummary(file.getFileId(), summary.strip(), llm.model()));
        return RuleSummaryResponse.of(file, saved, false);
    }

    /** LLM에 보내는 내용: 열 이름·대표 수식·벗어난 행 개수 (셀 값 없음) */
    String input(String sheetName, List<FormulaRulesResponse.Column> columns) {
        List<Map<String, Object>> rules = columns.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("column", c.column());
            m.put("formula", c.formula());
            Map<String, Object> ex = new LinkedHashMap<>();
            if (!c.hardcodedRows().isEmpty()) {
                ex.put("hardcodedRows", c.hardcodedRows().size());
            }
            if (!c.blankRows().isEmpty()) {
                ex.put("blankRows", c.blankRows().size());
            }
            if (!c.otherFormulaRows().isEmpty()) {
                ex.put("otherFormulaRows", c.otherFormulaRows().size());
            }
            if ("DIFFERENT".equals(c.acrossSheets())) {
                ex.put("otherSheetsUse", c.otherSheetsFormula());
            }
            if (!ex.isEmpty()) {
                m.put("exceptions", ex);
            }
            return m;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sheet", sheetName);
        body.put("rules", rules);
        return objectMapper.writeValueAsString(body);
    }
}
