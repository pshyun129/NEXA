package com.ssafy.migrationservice.domain.migration.reader;

import java.util.List;
import java.util.Map;

/**
 * 엑셀·CSV 시트 하나를 읽은 결과. 모든 값은 문자열로 보존하고 타입 해석은 검증 단계에서 한다.
 * rowNo는 원본 파일 기준 행 번호(1부터)라서 오류 위치를 그대로 안내할 수 있다.
 *
 * @param sheetName      엑셀 시트 이름 (CSV는 null)
 * @param headerRowNo    헤더로 고른 행 번호
 * @param totalRows      합계·소계로 보고 데이터에서 뺀 행
 * @param rowsAfterTotal 합계 행 아래에 있는 데이터 행 (합계에 빠졌을 수 있음)
 * @param preamble       헤더 위 제목·안내 행의 글자 (행마다 칸을 공백으로 이음). 근무표 'D 07:00~15:30' 같은 범례가 여기 있다
 */
public record ParsedTable(List<String> headers, List<Row> rows, String sheetName, int headerRowNo,
                          List<Integer> totalRows, List<Integer> rowsAfterTotal, List<String> preamble) {

    public ParsedTable(List<String> headers, List<Row> rows) {
        this(headers, rows, null, 0, List.of(), List.of(), List.of());
    }

    /** 수식 셀 수 (시트 목록에 보여 줌) */
    public int formulaCells() {
        return rows.stream().mapToInt(r -> r.formulas().size()).sum();
    }

    /**
     * @param formulas 헤더 → 수식 문자열 (= 없이, 수식 셀만)
     * @param errors   헤더 → 저장된 오류값 (#N/A, #REF! …). 이런 셀의 values 값은 null
     */
    public record Row(int rowNo, Map<String, String> values, Map<String, String> formulas, Map<String, String> errors) {

        public Row(int rowNo, Map<String, String> values) {
            this(rowNo, values, Map.of(), Map.of());
        }
    }
}
