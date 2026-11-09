package com.ssafy.user_service.domain.migration.service;

import com.ssafy.user_service.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 이관 한 행의 값 읽기. 값이 없거나(null·빈 문자열) 키가 없으면 "주어지지 않음"으로 보고,
 * 수정 시에는 기존 값을 유지한다. 형식 오류는 행 번호가 붙은 MIG_001 로 던진다
 * (이 메시지가 migration 화면의 batch 오류로 그대로 보인다).
 */
final class MigrationRow {

    private final int rowNo;
    private final Map<String, Object> data;
    private final Map<String, String> labels;

    MigrationRow(MigrationUpsertRequest.Row row, Map<String, String> labels) {
        this.rowNo = row.rowNo();
        this.data = row.data();
        this.labels = labels;
    }

    int rowNo() {
        return rowNo;
    }

    boolean has(String key) {
        return string(key) != null;
    }

    String string(String key) {
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        String s = (value instanceof BigDecimal d ? d.toPlainString() : value.toString()).trim();
        return s.isEmpty() ? null : s;
    }

    String requiredString(String key) {
        String s = string(key);
        if (s == null) {
            throw error(label(key) + " 값이 없습니다");
        }
        return s;
    }

    Integer integer(String key) {
        String s = string(key);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw error(label(key) + " 값 '" + s + "'은(는) 정수가 아닙니다");
        }
    }

    LocalDate date(String key) {
        String s = string(key);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw error(label(key) + " 값 '" + s + "'은(는) 날짜(yyyy-MM-dd)가 아닙니다");
        }
    }

    <E extends Enum<E>> E enumValue(String key, Class<E> type) {
        String s = string(key);
        if (s == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, s.toUpperCase());
        } catch (IllegalArgumentException e) {
            String allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
            throw error(label(key) + " 값 '" + s + "'을(를) 알 수 없습니다 (허용: " + allowed + ")");
        }
    }

    String label(String key) {
        return labels.getOrDefault(key, key);
    }

    BusinessException error(String message) {
        return new BusinessException(ErrorCode.MIGRATION_ROW_INVALID, "행 " + rowNo + ": " + message);
    }
}
