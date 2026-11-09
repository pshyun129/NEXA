package com.ssafy.migrationservice.domain.migration.validation.payroll;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegalStandardCheckerTest {

    private static final List<String> HEADERS = List.of("성명", "기본급", "식대", "통상시급", "연장", "휴일", "휴일 8h초과",
            "연장수당", "휴일수당", "지급계", "과세대상", "국민연금", "건강보험", "장기요양", "고용보험");

    @Test
    void 귀속연도는_시트_이름_다음_파일_이름에서_찾는다() {
        assertThat(LegalStandardChecker.payYear("2026-07", "급여대장.xlsx")).isEqualTo(2026);
        assertThat(LegalStandardChecker.payYear("7월", "2025년 급여대장.xlsx")).isEqualTo(2025);
        assertThat(LegalStandardChecker.payYear("7월", "급여대장.xlsx")).isNull();
    }

    @Test
    void 법정_기준대로_계산한_시트는_경고가_없다() {
        assertThat(check("2026-10", sheet(Options.normal()))).isEmpty();
    }

    @Test
    void 귀속연도와_다른_해_요율이면_시트_단위로_알린다() {
        List<ValidationIssue> issues = check("2026-07", sheet(Options.normal().pension("0.045")));

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo(IssueCode.INSURANCE_RATE_OUTDATED);
            assertThat(i.rowNo()).isNull();
            assertThat(i.sourceColumn()).isEqualTo("국민연금");
            assertThat(i.message()).contains("'과세대상' × 4.5%(2023~2025년 요율)", "2026년 요율은 4.75%");
            assertThat(i.suggestion()).isEqualTo("4.75%");
        });
        // 2025년 귀속 시트라면 4.5%가 맞다
        assertThat(check("2025-12", sheet(Options.normal().year(2025)))).isEmpty();
    }

    @Test
    void 귀속연도를_모르면_최신_요율_기준으로_조건부로_말한다() {
        List<ValidationIssue> issues = check("7월", sheet(Options.normal().pension("0.045")));

        assertThat(issues).singleElement()
                .satisfies(i -> assertThat(i.message()).contains("귀속연도를 찾지 못했습니다", "2026년 귀속이면"));
    }

    @Test
    void 일부_행만_상한_때문에_안_맞아도_대부분이_맞으면_경고하지_않는다() {
        Options o = Options.normal();
        o.cappedRows = 3;      // 국민연금 상한에 걸린 고소득자
        assertThat(check("2026-10", sheet(o))).isEmpty();
    }

    @Test
    void 비과세_식대를_포함한_지급계로_4대보험을_계산하면_한_건으로_알린다() {
        Options o = Options.normal();
        o.mealInBase = true;
        o.withTaxable = false;

        List<ValidationIssue> issues = check("2026-10", sheet(o));

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo(IssueCode.INSURANCE_BASE_INCLUDES_MEAL);
            assertThat(i.value()).isEqualTo("국민연금, 건강보험, 고용보험");
        });
    }

    @Test
    void 휴일_8시간_초과분까지_1_5배면_시트_단위로_알린다() {
        Options o = Options.normal();
        o.holidayOver8Flat = true;

        List<ValidationIssue> issues = check("2026-10", sheet(o));

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo(IssueCode.PREMIUM_BELOW_STATUTORY);
            assertThat(i.rowNo()).isNull();
            assertThat(i.sourceColumn()).isEqualTo("휴일수당");
            assertThat(i.message()).contains("1.5배", "2배");
        });
    }

    @Test
    void 연장수당이_법정_배율보다_적은_행을_알린다() {
        List<ParsedTable.Row> rows = sheet(Options.normal());
        ParsedTable.Row r = rows.get(4);
        Map<String, String> v = new LinkedHashMap<>(r.values());
        v.put("연장수당", "1000");
        rows.set(4, new ParsedTable.Row(r.rowNo(), v));

        List<ValidationIssue> issues = check("2026-10", rows).stream()
                .filter(i -> i.code() == IssueCode.PREMIUM_BELOW_STATUTORY).toList();

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.rowNo()).isEqualTo(r.rowNo());
            assertThat(i.sourceColumn()).isEqualTo("연장수당");
        });
    }

    @Test
    void 고정_지급_합이_최저임금_월_환산액보다_적은_행을_알린다() {
        List<ParsedTable.Row> rows = sheet(Options.normal());
        ParsedTable.Row r = rows.get(2);
        Map<String, String> v = new LinkedHashMap<>(r.values());
        v.put("기본급", "1800000");     // 1,800,000 + 식대 200,000 < 2,156,880 (연장·휴일수당은 산입하지 않음)
        rows.set(2, new ParsedTable.Row(r.rowNo(), v));

        List<ValidationIssue> issues = check("2026-10", rows).stream()
                .filter(i -> i.code() == IssueCode.BELOW_MINIMUM_WAGE).toList();

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.rowNo()).isEqualTo(r.rowNo());
            assertThat(i.message()).contains("'기본급' + '식대' 2,000,000원", "2,156,880원");
        });
        // 귀속연도를 모르면 하지 않는다
        assertThat(check("10월", rows)).noneMatch(i -> i.code() == IssueCode.BELOW_MINIMUM_WAGE);
    }

    @Test
    void 최저임금_미달이_많으면_단시간_근로자_자료로_보고_한_건으로_묶는다() {
        List<ParsedTable.Row> rows = new ArrayList<>();
        for (ParsedTable.Row r : sheet(Options.normal())) {
            Map<String, String> v = new LinkedHashMap<>(r.values());
            v.put("기본급", "1000000");
            rows.add(new ParsedTable.Row(r.rowNo(), v));
        }

        List<ValidationIssue> issues = check("2026-10", rows).stream()
                .filter(i -> i.code() == IssueCode.BELOW_MINIMUM_WAGE).toList();

        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.rowNo()).isNull();
            assertThat(i.message()).contains("12/12행", "단시간 근로자");
        });
    }

    // ---------- 데이터 ----------

    private static List<ValidationIssue> check(String sheet, List<ParsedTable.Row> rows) {
        return LegalStandardChecker.check(1L, sheet, "급여대장.xlsx", HEADERS, rows);
    }

    private static final class Options {
        String pension;
        int year = 2026;
        int cappedRows;
        boolean mealInBase;
        boolean withTaxable = true;
        boolean holidayOver8Flat;

        static Options normal() {
            return new Options();
        }

        Options pension(String rate) {
            this.pension = rate;
            return this;
        }

        Options year(int year) {
            this.year = year;
            return this;
        }
    }

    /** 12명. 짝수 행만 휴일근로, 4의 배수 행만 휴일 8시간 초과 */
    private static List<ParsedTable.Row> sheet(Options o) {
        StatutoryRates s = StatutoryRates.of(o.year).orElseThrow();
        BigDecimal np = o.pension == null ? s.pension() : new BigDecimal(o.pension);
        List<ParsedTable.Row> rows = new ArrayList<>();
        for (int n = 2; n <= 13; n++) {
            long base = 3_000_000 + n * 50_000L;
            long meal = 200_000;
            long hourly = base / 209;
            int ot = n % 5;
            int hol = n % 2 == 0 ? 8 : 0;
            int hol8 = n % 4 == 0 ? 2 : 0;
            long otPay = Math.round(hourly * ot * 1.5);
            long holPay = o.holidayOver8Flat ? Math.round(hourly * (hol + hol8) * 1.5) : Math.round(hourly * (hol * 1.5 + hol8 * 2.0));
            long gross = base + meal + otPay + holPay;
            long taxable = gross - meal;
            long insBase = o.mealInBase ? gross : taxable;
            long pension = floor10(BigDecimal.valueOf(insBase).multiply(np));
            if (n - 2 < o.cappedRows) {
                pension = 300_000;
            }
            long health = floor10(BigDecimal.valueOf(insBase).multiply(s.health()));
            long ltc = floor10(BigDecimal.valueOf(health).multiply(s.longTermCare()));
            long ei = floor10(BigDecimal.valueOf(insBase).multiply(s.employment()));

            Map<String, String> v = new LinkedHashMap<>();
            v.put("성명", "직원" + n);
            v.put("기본급", String.valueOf(base));
            v.put("식대", String.valueOf(meal));
            v.put("통상시급", String.valueOf(hourly));
            v.put("연장", String.valueOf(ot));
            v.put("휴일", String.valueOf(hol));
            v.put("휴일 8h초과", String.valueOf(hol8));
            v.put("연장수당", String.valueOf(otPay));
            v.put("휴일수당", String.valueOf(holPay));
            v.put("지급계", String.valueOf(gross));
            v.put("과세대상", o.withTaxable ? String.valueOf(taxable) : null);
            v.put("국민연금", String.valueOf(pension));
            v.put("건강보험", String.valueOf(health));
            v.put("장기요양", String.valueOf(ltc));
            v.put("고용보험", String.valueOf(ei));
            rows.add(new ParsedTable.Row(n, v));
        }
        return rows;
    }

    private static long floor10(BigDecimal v) {
        return v.movePointLeft(1).setScale(0, RoundingMode.DOWN).movePointRight(1).longValue();
    }
}
