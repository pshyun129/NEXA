package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.MigrationFile;
import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 교대 근무표 검사. 날짜마다 근무 코드(D·E·N …)를 적은 표에서, 헤더 위 범례('D 07:00~15:30 / N 23:00~07:30')로
 * 코드의 근무 시각을 알 때만 본다. 범례에 시각이 없는 코드(O·연 등)는 쉬는 날로 본다.
 * - 앞날 근무가 끝나기 전에 다음 날 근무가 시작함 (있을 수 없는 배치)
 * - 근무 사이 휴식 11시간 미만: 근로시간 특례 업종(보건업·운송업, 근로기준법 제59조)은 연속 11시간 휴식을 줘야 한다
 * - 7일 이상 쉬는 날 없이 연속 근무: 주 1회 이상 휴일(제55조)은 교대제에서 기간 평균으로 따지므로 확인 대상으로만 알린다
 */
final class DutyRosterChecker {

    private static final Pattern LEGEND = Pattern.compile(
            "(?<![A-Za-z가-힣0-9])([A-Za-z가-힣]{1,2})\\s*[:=]?\\s*(\\d{1,2}):(\\d{2})\\s*[~∼\\-–]\\s*(\\d{1,2}):(\\d{2})");
    private static final int MIN_REST_MINUTES = 11 * 60;
    private static final int MAX_CONSECUTIVE_DAYS = 6;

    /** 분 단위 시작·끝. 끝이 시작보다 이르면 다음 날 끝남 */
    record Shift(int start, int end) {
        int endFromStartDay() {
            return end <= start ? end + 24 * 60 : end;
        }
    }

    private DutyRosterChecker() {
    }

    static List<ValidationIssue> check(MigrationFile file, List<ParsedTable.Row> rows) {
        TreeMap<Integer, String> days = DayGrid.columns(List.of(file.getHeaders()));
        Map<String, Shift> legend = legend(file.getPreamble());
        if (days.isEmpty() || legend.size() < 2) {
            return List.of();
        }
        List<ValidationIssue> issues = new ArrayList<>();
        for (ParsedTable.Row row : rows) {
            int runStart = 0;
            int run = 0;
            for (Map.Entry<Integer, String> e : days.entrySet()) {
                int day = e.getKey();
                Shift today = shift(legend, row.values().get(e.getValue()));
                Shift yesterday = day == 1 ? null : shift(legend, row.values().get(days.get(day - 1)));
                if (today != null && yesterday != null) {
                    int rest = 24 * 60 + today.start() - yesterday.endFromStartDay();
                    String codes = row.values().get(days.get(day - 1)) + " → " + row.values().get(e.getValue());
                    if (rest < 0) {
                        issues.add(issue(file, row, e.getValue(), IssueCode.SHIFT_OVERLAP,
                                (day - 1) + "일 근무가 끝나기 전에 " + day + "일 근무가 시작합니다 (" + codes + ", " + hours(-rest)
                                        + " 겹침). 근무 코드가 잘못 들어갔을 수 있습니다", codes));
                    } else if (rest < MIN_REST_MINUTES) {
                        issues.add(issue(file, row, e.getValue(), IssueCode.REST_INTERVAL_SHORT,
                                (day - 1) + "일 → " + day + "일 근무 사이 휴식이 " + hours(rest) + "입니다 (" + codes
                                        + "). 근로시간 특례 업종(보건업·운송업 등)은 연속 11시간 이상 쉬어야 합니다", codes));
                    }
                }
                if (today != null) {
                    runStart = run == 0 ? day : runStart;
                    run++;
                }
                if (run > MAX_CONSECUTIVE_DAYS && (today == null || day == days.lastKey())) {
                    int last = today == null ? day - 1 : day;
                    issues.add(issue(file, row, days.get(runStart + MAX_CONSECUTIVE_DAYS), IssueCode.CONSECUTIVE_WORK_DAYS,
                            runStart + "일 ~ " + last + "일 " + run + "일 동안 쉬는 날 없이 근무합니다. "
                                    + "주 1회 이상 휴일(근로기준법 제55조)이 지켜지는지 확인해 주세요", String.valueOf(run)));
                }
                if (today == null) {
                    run = 0;
                }
            }
        }
        return issues;
    }

    /** 범례 '코드 HH:MM~HH:MM' → 코드(대문자) → 근무 시각 */
    static Map<String, Shift> legend(String preamble) {
        Map<String, Shift> out = new HashMap<>();
        if (preamble == null) {
            return out;
        }
        Matcher m = LEGEND.matcher(preamble);
        while (m.find()) {
            out.putIfAbsent(m.group(1).toUpperCase(Locale.ROOT), new Shift(minutes(m.group(2), m.group(3)), minutes(m.group(4), m.group(5))));
        }
        return out;
    }

    private static Shift shift(Map<String, Shift> legend, String code) {
        return code == null ? null : legend.get(code.strip().toUpperCase(Locale.ROOT));
    }

    private static int minutes(String h, String m) {
        return Integer.parseInt(h) * 60 + Integer.parseInt(m);
    }

    private static String hours(int minutes) {
        return minutes / 60 + "시간" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "분");
    }

    private static ValidationIssue issue(MigrationFile file, ParsedTable.Row row, String column, IssueCode code, String message, String value) {
        return new ValidationIssue(file.getFileId(), row.rowNo(), null, column, Severity.WARN, code, message, value, null);
    }
}
