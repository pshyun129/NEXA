package com.ssafy.payrollservice.domain.migration.service;

import com.ssafy.payrollservice.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.payrollservice.global.exception.BusinessException;
import com.ssafy.payrollservice.global.exception.ErrorCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 이관 한 행의 값 읽기 (user-service 이관 API와 같은 규칙). 값이 null·빈 문자열이면 "주어지지 않음".
 * 형식 오류는 행 번호가 붙은 MIG_001 로 던진다 (이 메시지가 migration 화면의 batch 오류로 그대로 보인다).
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

    Object raw(String key) {
        return data.get(key);
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

    /** 정수 금액·ID. 소수점 아래가 0이 아니면 오류 */
    Long longValue(String key) {
        String s = string(key);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(",", "")).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw error(label(key) + " 값 '" + s + "'은(는) 정수가 아닙니다");
        }
    }

    long requiredLong(String key) {
        Long v = longValue(key);
        if (v == null) {
            throw error(label(key) + " 값이 없습니다");
        }
        return v;
    }

    Integer integer(String key) {
        Long v = longValue(key);
        if (v != null && (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE)) {
            throw error(label(key) + " 값 " + v + "이(가) 너무 큽니다");
        }
        return v == null ? null : v.intValue();
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

    String label(String key) {
        return labels.getOrDefault(key, key);
    }

    BusinessException error(String message) {
        return new BusinessException(ErrorCode.MIGRATION_ROW_INVALID, "행 " + rowNo + ": " + message);
    }
}
