package com.ssafy.migrationservice.domain.migration.validation.payroll;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.GROSS;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.HOURLY;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.MEAL;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.NOT_AMOUNT;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.PAY_ITEM;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.SUBTOTAL;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.TAXABLE;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.find;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.normalize;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.number;
import static com.ssafy.migrationservice.domain.migration.validation.payroll.PayrollColumns.numeric;

/**
 * 급여대장 값을 법정 기준과 비교한다. 수식이 아니라 값으로 보므로 설정 시트를 참조하는 수식·CSV에도 쓸 수 있다.
 * - 4대보험: 열마다 (기준 금액, 요율) 조합 중 가장 많은 행을 설명하는 것을 찾아, 귀속연도 요율과 다르면 경고.
 *   기준 금액이 비과세 식대를 포함한 지급계이면 경고. 국민연금은 기준소득월액·상하한 때문에 행마다 맞지 않을 수 있어 시트 단위로만 본다
 * - 가산수당: 통상시급 × 시간 × 법정 배율(연장 1.5, 야간 0.5, 휴일 8시간까지 1.5·초과 2.0)보다 적은 행
 * - 최저임금: 고정 지급 항목(가산수당 제외) 합 ÷ 209시간이 귀속연도 최저시급보다 적은 행
 */
public final class LegalStandardChecker {

    private static final BigDecimal TOLERANCE = BigDecimal.TEN;     // 10원 단위 버림·반올림, 원 단위 절사
    private static final double MAJORITY = 0.6;
    private static final double MOSTLY = 0.8;
    private static final int MIN_ROWS = 3;
    private static final Pattern YEAR = Pattern.compile("(20\\d{2})");
    private static final Pattern REPORTED_WAGE = Pattern.compile(".*(기준소득월액|신고소득월액|보수월액)");
    private static final Pattern NOT_PREMIUM = Pattern.compile("율|rate|기준|보수|월액|여부|번호|코드");

    private enum Insurance {
        PENSION("^(국민연금|nps|pension)", StatutoryRates::pension),
        HEALTH("^(건강보험|건보료|nhis|health)", StatutoryRates::health),
        LONG_TERM_CARE("^(장기요양|노인장기요양|요양보험|ltc|longtermcare)", StatutoryRates::longTermCare),
        EMPLOYMENT("^(고용보험|eis|employment)", StatutoryRates::employment);

        final Pattern header;
        final Function<StatutoryRates, BigDecimal> rate;

        Insurance(String header, Function<StatutoryRates, BigDecimal> rate) {
            this.header = Pattern.compile(header);
            this.rate = rate;
        }
    }

    private enum Premium {
        OVERTIME("연장|시간외|^ot|overtime", "연장", "1.5"),
        NIGHT("야간|^night", "야간", "0.5"),
        HOLIDAY("휴일|특근|^hol", "휴일", "1.5");

        final Pattern keyword;
        final String label;
        final BigDecimal rate;

        Premium(String keyword, String label, String rate) {
            this.keyword = Pattern.compile(keyword);
            this.label = label;
            this.rate = new BigDecimal(rate);
        }
    }

    private static final BigDecimal HOLIDAY_OVER_8H = new BigDecimal("2.0");

    private LegalStandardChecker() {
    }

    public static List<ValidationIssue> check(Long fileId, String sheetName, String fileName, List<String> headers,
                                              List<ParsedTable.Row> rows) {
        Integer year = payYear(sheetName, fileName);
        List<ValidationIssue> issues = new ArrayList<>();
        insurance(fileId, year, headers, rows, issues);
        premiums(fileId, headers, rows, issues);
        minimumWage(fileId, year, headers, rows, issues);
        return issues;
    }

    /** 시트 이름 → 파일 이름 순으로 찾은 귀속연도 (2026-07, 2026년 7월 …) */
    static Integer payYear(String sheetName, String fileName) {
        for (String s : new String[]{sheetName, fileName}) {
            Matcher m = s == null ? null : YEAR.matcher(s);
            if (m != null && m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return null;
    }

    // ---------- 4대보험 ----------

    private record Base(String label, Function<ParsedTable.Row, Optional<BigDecimal>> amount, boolean includesMeal) {
    }

    private record Fit(Base base, BigDecimal rate, int rows) {
    }

    private static void insurance(Long fileId, Integer year, List<String> headers, List<ParsedTable.Row> rows,
                                  List<ValidationIssue> issues) {
        StatutoryRates target = year == null ? StatutoryRates.latest() : StatutoryRates.of(year).orElse(null);
        if (target == null) {
            return;     // 표에 없는 연도: 비교할 기준이 없음
        }
        int gross = find(headers, GROSS);
        int taxable = find(headers, TAXABLE);
        int meal = find(headers, MEAL);
        List<Base> wageBases = new ArrayList<>();
        // 공단에 신고한 보수월액·기준소득월액 열이 있으면 그것부터 (국민연금은 월 급여가 아니라 이 금액에 요율을 곱한다)
        for (String h : headers) {
            if (REPORTED_WAGE.matcher(normalize(h)).matches() && numeric(rows, h)) {
                wageBases.add(new Base("'" + h + "'", value(h), false));
            }
        }
        if (taxable >= 0) {
            wageBases.add(new Base("'" + headers.get(taxable) + "'", value(headers.get(taxable)), false));
        }
        if (gross >= 0 && meal >= 0) {
            String g = headers.get(gross);
            String m = headers.get(meal);
            wageBases.add(new Base("'" + g + "' − 비과세 식대", r -> value(g).apply(r).map(v -> v.subtract(
                    value(m).apply(r).orElse(BigDecimal.ZERO).min(target.mealLimit()))), false));
        }
        if (gross >= 0) {
            wageBases.add(new Base("'" + headers.get(gross) + "'", value(headers.get(gross)), meal >= 0));
        }

        Map<Insurance, String> columns = new LinkedHashMap<>();
        for (Insurance ins : Insurance.values()) {
            for (String h : headers) {
                String n = normalize(h);
                if (ins.header.matcher(n).find() && !NOT_PREMIUM.matcher(n).find() && numeric(rows, h)) {
                    columns.putIfAbsent(ins, h);
                }
            }
        }

        List<String> mealIncluded = new ArrayList<>();
        String mealBase = null;
        for (Map.Entry<Insurance, String> e : columns.entrySet()) {
            Insurance ins = e.getKey();
            String column = e.getValue();
            List<Base> bases = ins == Insurance.LONG_TERM_CARE
                    ? Optional.ofNullable(columns.get(Insurance.HEALTH)).map(h -> List.of(new Base("'" + h + "'", value(h), false))).orElse(List.of())
                    : wageBases;
            Optional<Fit> fit = bestFit(rows, column, bases, ins, target);
            if (fit.isEmpty()) {
                continue;
            }
            Fit f = fit.get();
            long nonzero = rows.stream().filter(r -> value(column).apply(r).map(v -> v.signum() > 0).orElse(false)).count();
            BigDecimal statutory = ins.rate.apply(target);
            if (f.rate().compareTo(statutory) != 0) {
                String when = year == null
                        ? "시트·파일 이름에서 귀속연도를 찾지 못했습니다. " + target.year() + "년 귀속이면 요율은 " + pct(statutory) + "입니다"
                        : target.year() + "년 요율은 " + pct(statutory) + "입니다. 작년 양식을 복사했거나 수식에 요율을 숫자로 넣었는지 확인해 주세요";
                issues.add(new ValidationIssue(fileId, null, null, column, Severity.WARN, IssueCode.INSURANCE_RATE_OUTDATED,
                        "'" + column + "'이(가) " + f.base().label() + " × " + pct(f.rate()) + "(" + years(ins, f.rate()) + " 요율)로 계산돼 있습니다 ("
                                + f.rows() + "/" + nonzero + "행). " + when, pct(f.rate()), pct(statutory)));
            }
            if (f.base().includesMeal() && mealNonzero(rows, headers.get(meal))) {
                mealIncluded.add(column);
                mealBase = f.base().label();
            }
        }
        if (!mealIncluded.isEmpty()) {
            String names = mealIncluded.stream().map(c -> "'" + c + "'").collect(Collectors.joining(", "));
            issues.add(new ValidationIssue(fileId, null, null, mealIncluded.get(0), Severity.WARN, IssueCode.INSURANCE_BASE_INCLUDES_MEAL,
                    names + "이(가) 비과세 식대를 포함한 " + mealBase + " 기준으로 계산돼 있습니다. 4대보험 보수월액에는 비과세 소득(식대 월 "
                            + won(target.mealLimit()) + "원까지)이 들어가지 않습니다", String.join(", ", mealIncluded), null));
        }
    }

    /** 가장 많은 행을 설명하는 (기준 금액, 요율). 같으면 귀속연도 요율·앞쪽 기준(과세대상)을 고른다. 과반이 안 되면 empty */
    private static Optional<Fit> bestFit(List<ParsedTable.Row> rows, String column, List<Base> bases, Insurance ins,
                                         StatutoryRates target) {
        List<BigDecimal> rates = Stream.concat(Stream.of(ins.rate.apply(target)), StatutoryRates.all().stream().map(ins.rate))
                .distinct().toList();
        List<ParsedTable.Row> data = rows.stream().filter(r -> r.errors().isEmpty()
                && value(column).apply(r).map(v -> v.signum() > 0).orElse(false)).toList();
        if (data.size() < MIN_ROWS) {
            return Optional.empty();
        }
        Fit best = null;
        for (Base base : bases) {
            for (BigDecimal rate : rates) {
                int n = 0;
                for (ParsedTable.Row r : data) {
                    BigDecimal amount = value(column).apply(r).orElseThrow();
                    Optional<BigDecimal> b = base.amount().apply(r);
                    if (b.isPresent() && amount.subtract(b.get().multiply(rate)).abs().compareTo(TOLERANCE) <= 0) {
                        n++;
                    }
                }
                if (best == null || n > best.rows()) {
                    best = new Fit(base, rate, n);
                }
            }
        }
        return best != null && best.rows() >= data.size() * MAJORITY ? Optional.of(best) : Optional.empty();
    }

    private static boolean mealNonzero(List<ParsedTable.Row> rows, String meal) {
        long n = rows.stream().filter(r -> value(meal).apply(r).map(v -> v.signum() > 0).orElse(false)).count();
        return n >= rows.size() * MAJORITY;
    }

    private static String years(Insurance ins, BigDecimal rate) {
        List<Integer> ys = StatutoryRates.all().stream().filter(s -> ins.rate.apply(s).compareTo(rate) == 0)
                .map(StatutoryRates::year).toList();
        return ys.size() == 1 ? ys.get(0) + "년" : ys.get(0) + "~" + ys.get(ys.size() - 1) + "년";
    }

    // ---------- 가산수당 ----------

    private static void premiums(Long fileId, List<String> headers, List<ParsedTable.Row> rows, List<ValidationIssue> issues) {
        int hourlyCol = find(headers, HOURLY);
        if (hourlyCol < 0 || !numeric(rows, headers.get(hourlyCol))) {
            return;
        }
        String hourly = headers.get(hourlyCol);
        for (Premium p : Premium.values()) {
            List<String> pay = new ArrayList<>();
            List<String> hours = new ArrayList<>();
            List<String> over8 = new ArrayList<>();
            for (String h : headers) {
                String n = normalize(h);
                if (h.equals(hourly) || !p.keyword.matcher(n).find() || NOT_PREMIUM.matcher(n).find() || !numeric(rows, h)) {
                    continue;
                }
                if (PAY_ITEM.matcher(n).find()) {
                    pay.add(h);
                } else if (p == Premium.HOLIDAY && n.contains("8")) {
                    over8.add(h);
                } else {
                    hours.add(h);
                }
            }
            if (pay.size() == 1 && hours.size() == 1 && over8.size() <= 1) {
                premium(fileId, p, hourly, pay.get(0), hours.get(0), over8.isEmpty() ? null : over8.get(0), rows, issues);
            }
        }
    }

    private record Short(int rowNo, BigDecimal pay, BigDecimal required, boolean flatOver8) {
    }

    private static void premium(Long fileId, Premium p, String hourly, String pay, String hours, String over8,
                                List<ParsedTable.Row> rows, List<ValidationIssue> issues) {
        List<Short> shorts = new ArrayList<>();
        int withOver8 = 0;
        for (ParsedTable.Row r : rows) {
            if (Stream.of(hourly, pay, hours, over8).anyMatch(c -> c != null && r.errors().containsKey(c))) {
                continue;
            }
            Optional<BigDecimal> h = value(hourly).apply(r);
            BigDecimal within = value(hours).apply(r).orElse(BigDecimal.ZERO);
            BigDecimal beyond = over8 == null ? BigDecimal.ZERO : value(over8).apply(r).orElse(BigDecimal.ZERO);
            if (h.isEmpty()) {
                continue;
            }
            if (beyond.signum() > 0) {
                withOver8++;
            }
            BigDecimal required = h.get().multiply(within.multiply(p.rate).add(beyond.multiply(HOLIDAY_OVER_8H)));
            BigDecimal actual = value(pay).apply(r).orElse(BigDecimal.ZERO);
            if (required.subtract(actual).compareTo(TOLERANCE) > 0) {
                BigDecimal flat = h.get().multiply(within.add(beyond)).multiply(p.rate);
                shorts.add(new Short(r.rowNo(), actual, required, beyond.signum() > 0
                        && actual.subtract(flat).abs().compareTo(TOLERANCE) <= 0));
            }
        }
        long flat = shorts.stream().filter(Short::flatOver8).count();
        if (over8 != null && withOver8 >= MIN_ROWS && shorts.size() >= withOver8 * MOSTLY && flat >= shorts.size() * MOSTLY) {
            issues.add(new ValidationIssue(fileId, null, null, pay, Severity.WARN, IssueCode.PREMIUM_BELOW_STATUTORY,
                    "'" + pay + "'이(가) '" + over8 + "'까지 1.5배로 계산돼 있습니다 (" + flat + "행). 휴일근로 8시간 초과분은 통상임금의 "
                            + "2배입니다 (근로기준법 제56조, 상시 5인 미만 사업장은 적용 제외)", pay, null));
            return;
        }
        for (Short s : shorts) {
            String formula = "'" + hourly + "' × '" + hours + "'" + " × " + p.rate.stripTrailingZeros().toPlainString()
                    + (over8 == null ? "" : " + '" + hourly + "' × '" + over8 + "' × 2");
            issues.add(new ValidationIssue(fileId, s.rowNo(), null, pay, Severity.WARN, IssueCode.PREMIUM_BELOW_STATUTORY,
                    "'" + pay + "' " + won(s.pay()) + "원이 법정 " + p.label + "근로 가산(" + formula + " = " + won(s.required())
                            + "원)보다 " + won(s.required().subtract(s.pay())) + "원 적습니다 (상시 5인 미만 사업장은 적용 제외)",
                    s.pay().stripTrailingZeros().toPlainString(), s.required().setScale(0, RoundingMode.DOWN).toPlainString()));
        }
    }

    // ---------- 최저임금 ----------

    private static final double MINIMUM_WAGE_COLLAPSE = 0.3;

    /**
     * 지급계 왼쪽의 지급 항목 중 연장·야간·휴일 수당을 뺀 열의 합을 월 209시간으로 나눠 최저시급과 비교한다
     * (2024년부터 정기상여·복리후생비도 전액 산입). 단시간 근로자·월 중 입사자는 209시간 기준이 아니어서
     * 시트의 30% 넘게 걸리면 행마다 알리지 않고 한 건으로 묶는다. 귀속연도를 모르면 하지 않는다
     */
    private static void minimumWage(Long fileId, Integer year, List<String> headers, List<ParsedTable.Row> rows,
                                    List<ValidationIssue> issues) {
        StatutoryRates target = year == null ? null : StatutoryRates.of(year).orElse(null);
        int gross = find(headers, GROSS);
        if (target == null || target.minimumWage() == null || gross < 0) {
            return;
        }
        List<String> items = new ArrayList<>();
        for (String h : headers.subList(0, gross)) {
            String n = normalize(h);
            boolean premium = Stream.of(Premium.values()).anyMatch(p -> p.keyword.matcher(n).find());
            if (PAY_ITEM.matcher(n).find() && !NOT_AMOUNT.matcher(n).find() && !SUBTOTAL.matcher(n).matches()
                    && !premium && numeric(rows, h)) {
                items.add(h);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        BigDecimal monthly = target.minimumWage().multiply(StatutoryRates.MONTHLY_HOURS);
        Map<Integer, BigDecimal> below = new LinkedHashMap<>();     // 행 → 고정 지급 합
        int checked = 0;
        for (ParsedTable.Row r : rows) {
            if (items.stream().anyMatch(r.errors()::containsKey)) {
                continue;
            }
            BigDecimal sum = items.stream().map(h -> value(h).apply(r).orElse(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.signum() <= 0) {
                continue;
            }
            checked++;
            if (sum.compareTo(monthly) < 0) {
                below.put(r.rowNo(), sum);
            }
        }
        String basis = items.stream().map(h -> "'" + h + "'").collect(Collectors.joining(" + "));
        String standard = target.year() + "년 최저시급 " + won(target.minimumWage()) + "원 × 209시간 = " + won(monthly) + "원";
        if (below.size() > checked * MINIMUM_WAGE_COLLAPSE) {
            issues.add(new ValidationIssue(fileId, null, null, items.get(0), Severity.WARN, IssueCode.BELOW_MINIMUM_WAGE,
                    below.size() + "/" + checked + "행의 " + basis + "이(가) " + standard + "보다 적습니다. 단시간 근로자·월 중 입사자 자료이면 "
                            + "209시간 기준이 아니므로 근로시간에 맞춰 확인해 주세요", String.valueOf(below.size()), null));
            return;
        }
        below.forEach((rowNo, sum) -> {
            issues.add(new ValidationIssue(fileId, rowNo, null, items.get(0), Severity.WARN, IssueCode.BELOW_MINIMUM_WAGE,
                    basis + " " + won(sum) + "원이 " + standard + "보다 " + won(monthly.subtract(sum)) + "원 적습니다. "
                            + "주 40시간 근로자 기준이며, 단시간 근로·월 중 입사·무급 휴직이면 정상일 수 있습니다",
                    sum.toPlainString(), monthly.toPlainString()));
        });
    }

    // ---------- 공통 ----------

    private static Function<ParsedTable.Row, Optional<BigDecimal>> value(String column) {
        return r -> number(r.values().get(column));
    }

    private static String pct(BigDecimal rate) {
        return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    private static String won(BigDecimal v) {
        return new DecimalFormat("#,##0").format(v);
    }
}
