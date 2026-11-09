package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.FieldValueChecker;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchema;
import com.ssafy.migrationservice.domain.migration.validation.schema.FieldSpec;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 임시 테이블 행(원본 컬럼명 → 문자열) → 대상 서비스로 보낼 행(필드명 → 정리된 값).
 * 검증과 같은 해석 규칙을 쓴다: 날짜는 ISO, 숫자는 쉼표 제거, 코드값은 한글 표기를 코드로.
 * 적재는 자동 정리(FIX) 0건일 때만 하므로 날짜·숫자는 이미 정리돼 있다. 여기서는 코드값 변환이 주 역할이고,
 * 미리보기에서 정리 전 값도 최종 모습으로 보여주기 위해 날짜·숫자 정리도 같이 한다.
 */
public final class RowTransformer {

    private RowTransformer() {
    }

    /** fieldToColumn: 필드 → 원본 컬럼 (연결된 것만) */
    public static List<LoadRow> transform(EntitySchema schema, Map<String, String> fieldToColumn,
                                          List<ParsedTable.Row> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> data = new LinkedHashMap<>();
            fieldToColumn.forEach((field, column) -> {
                FieldSpec f = schema.field(field).orElseThrow();
                data.put(field, normalize(f, row.values().get(column)));
            });
            return new LoadRow(row.rowNo(), data);
        }).toList();
    }

    static Object normalize(FieldSpec f, String value) {
        if (value == null) {
            return null;
        }
        return switch (f.type()) {
            case DATE -> FieldValueChecker.parseDate(value).map(Object::toString).orElse(value);
            case NUMBER -> new BigDecimal(value.replace(",", ""));
            case ENUM -> f.enumValues().getOrDefault(value, value.toUpperCase(Locale.ROOT));
            case STRING, EMAIL, PHONE -> value;
        };
    }
}
