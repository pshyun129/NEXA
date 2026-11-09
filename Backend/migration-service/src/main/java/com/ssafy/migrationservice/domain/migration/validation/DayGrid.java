package com.ssafy.migrationservice.domain.migration.validation;

import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 날짜마다 열이 하나인 표(근무표·출역부·근무시간표): 헤더가 1, 2, … 또는 1(화), 2(수) … 인 열.
 * 1일부터 28일 이상 이어질 때만 날짜 열로 본다 (번호·주차 열을 날짜로 잘못 읽지 않게).
 */
final class DayGrid {

    private static final Pattern DAY = Pattern.compile("(\\d{1,2})일?(?:\\s*\\(.\\))?");
    private static final int MIN_DAYS = 28;

    private DayGrid() {
    }

    /** 날짜 → 헤더. 날짜 열 표가 아니면 빈 맵 */
    static TreeMap<Integer, String> columns(List<String> headers) {
        TreeMap<Integer, String> days = new TreeMap<>();
        for (String h : headers) {
            Matcher m = DAY.matcher(h.strip());
            if (m.matches()) {
                int d = Integer.parseInt(m.group(1));
                if (d == days.size() + 1) {
                    days.put(d, h);
                }
            }
        }
        return days.size() >= MIN_DAYS ? days : new TreeMap<>();
    }
}
