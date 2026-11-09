package com.ssafy.migrationservice.domain.migration.dto;

import java.util.List;
import java.util.Map;

/**
 * 엑셀 수식에서 뽑은 회사 규칙. 열마다 대표 수식({헤더} 표기)과 그 규칙에서 벗어난 행.
 * acrossSheets: 같은 양식의 다른 시트와 비교 (SAME / DIFFERENT / NO_OTHER_SHEET)
 */
public record FormulaRulesResponse(Long fileId, String sheetName, List<Column> columns) {

    public record Column(String column, String formula, boolean formulaColumn, int dataRows, int formulaRows,
                         List<Integer> hardcodedRows, List<Integer> blankRows, Map<Integer, String> otherFormulaRows,
                         String acrossSheets, String otherSheetsFormula, List<String> otherSheets) {
    }
}
