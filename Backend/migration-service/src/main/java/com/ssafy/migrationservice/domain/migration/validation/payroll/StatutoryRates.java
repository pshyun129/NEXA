package com.ssafy.migrationservice.domain.migration.validation.payroll;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 연도별 법정 기준 (근로자 부담분). 새 해 요율이 고시되면 여기에 한 줄 추가한다.
 * 출처: 국민연금공단·국민건강보험공단 고시, 고용노동부 최저임금 고시 (2026-11 확인)
 *
 * @param pension        국민연금 근로자 요율
 * @param health         건강보험 근로자 요율
 * @param longTermCare   장기요양보험료 = 건강보험료 × 이 비율
 * @param employment     고용보험(실업급여) 근로자 요율
 * @param mealLimit      식대 비과세 한도 (월)
 * @param minimumWage    최저시급 (모르면 null)
 */
public record StatutoryRates(int year, BigDecimal pension, BigDecimal health, BigDecimal longTermCare,
                             BigDecimal employment, BigDecimal mealLimit, BigDecimal minimumWage) {

    /** 월 소정근로시간 (주 40시간 + 주휴) */
    public static final BigDecimal MONTHLY_HOURS = new BigDecimal("209");

    private static final TreeMap<Integer, StatutoryRates> BY_YEAR = new TreeMap<>(Map.of(
            2023, of(2023, "0.045", "0.03545", "0.1281", "0.009", "200000", null),
            2024, of(2024, "0.045", "0.03545", "0.1295", "0.009", "200000", "9860"),
            2025, of(2025, "0.045", "0.03545", "0.1295", "0.009", "200000", "10030"),
            2026, of(2026, "0.0475", "0.03595", "0.1314", "0.009", "200000", "10320")));

    public static Optional<StatutoryRates> of(int year) {
        return Optional.ofNullable(BY_YEAR.get(year));
    }

    public static StatutoryRates latest() {
        return BY_YEAR.lastEntry().getValue();
    }

    public static List<StatutoryRates> all() {
        return List.copyOf(BY_YEAR.values());
    }

    private static StatutoryRates of(int year, String np, String hi, String ltc, String ei, String meal, String minWage) {
        return new StatutoryRates(year, new BigDecimal(np), new BigDecimal(hi), new BigDecimal(ltc), new BigDecimal(ei),
                new BigDecimal(meal), minWage == null ? null : new BigDecimal(minWage));
    }
}
