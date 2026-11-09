package com.ssafy.migrationservice.domain.migration.dto;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;

/** 업로드한 엑셀의 시트 하나: 이름, 데이터 행 수, 헤더 행, 수식 셀 수 */
public record SheetInfo(String name, int rows, int headerRow, int formulaCells) {

    public static SheetInfo of(ParsedTable t) {
        return new SheetInfo(t.sheetName(), t.rows().size(), t.headerRowNo(), t.formulaCells());
    }
}
