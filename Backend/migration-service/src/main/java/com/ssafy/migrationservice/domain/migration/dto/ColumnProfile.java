package com.ssafy.migrationservice.domain.migration.dto;

import java.util.List;

/** detectedType: DATE / NUMBER / STRING / EMPTY. dateFormats는 DATE일 때 섞여 있는 날짜 표기 종류 */
public record ColumnProfile(String sourceColumn, String detectedType, double nullRate, int distinctCount,
                            List<String> dateFormats) {
}
