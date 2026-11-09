package com.ssafy.migrationservice.domain.migration.validation.payroll;

import com.ssafy.migrationservice.domain.migration.reader.ParsedTable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 급여대장 열 이름 사전과 숫자 읽기. 열 이름은 소문자로 바꾸고 공백·밑줄을 지운 뒤(normalize) 비교한다.
 * 회사마다 다른 이름(지급계·지급합계·GROSS)을 흔히 쓰는 급여 용어로만 묶는다.
 */
public final class PayrollColumns {

    public static final Pattern GROSS = Pattern.compile("지급(액)?(합계|총액|계)|총지급(액)?|급여(총액|합계)|gross(pay)?|totalpay|paytotal");
    public static final Pattern DEDUCTION_TOTAL = Pattern.compile("공제(액)?(합계|총액|계)|총공제(액)?|dedtotal|totalded(uction)?s?|deductiontotal");
    public static final Pattern NET = Pattern.compile("(실|차인|차감)지급(액)?|실수령(액)?|netpay|net");
    public static final Pattern PAY_ITEM = Pattern.compile("기본급|봉급|급여|수당|식대|급식비|보조금|보조비|활동비|지원금|장려금|상여|성과급|보너스|인센티브|pay|alw|allowance|meal|bonus");
    public static final Pattern SUBTOTAL = Pattern.compile(".*(계|총액|total)");    // 수당계·과세계·비과세계·소계
    public static final Pattern NOT_AMOUNT = Pattern.compile("시간(?!외)|시급|일수(?!당)|가족수(?!당)|과세(대상|표준|소득|급여|합계)|기준소득|소득월액|보수월액|율|hrs|hour|rate|taxable|cnt|count");
    public static final Pattern TAXABLE = Pattern.compile("과세(대상|표준|소득|급여|합계)(액)?|taxable(pay|income)?");
    public static final Pattern MEAL = Pattern.compile("식대|식비|중식대|meal(allowance)?");
    public static final Pattern HOURLY = Pattern.compile("통상시급|시급|통상시간급|hourly(wage|rate)?");

    private PayrollColumns() {
    }

    public static String normalize(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "");
    }

    /** 이름이 패턴과 통째로 맞는 첫 열. 없으면 두 줄 헤더('지급계\nGross' → '지급계 Gross')의 첫 낱말로, 그래도 없으면 -1 */
    public static int find(List<String> headers, Pattern p) {
        for (int c = 0; c < headers.size(); c++) {
            if (p.matcher(normalize(headers.get(c))).matches()) {
                return c;
            }
        }
        for (int c = 0; c < headers.size(); c++) {
            String h = headers.get(c).strip();
            if (h.contains(" ") && p.matcher(normalize(h.substring(0, h.indexOf(' ')))).matches()) {
                return c;
            }
        }
        return -1;
    }

    /** 값이 있는 칸의 80% 이상이 숫자인 열 */
    public static boolean numeric(List<ParsedTable.Row> rows, String header) {
        List<String> values = rows.stream().map(r -> r.values().get(header)).filter(Objects::nonNull).toList();
        long n = values.stream().filter(v -> number(v).isPresent()).count();
        return !values.isEmpty() && n >= values.size() * 0.8;
    }

    /** 쉼표·'원'을 뺀 숫자. 숫자가 아니면 empty */
    public static Optional<BigDecimal> number(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.replace(",", "").replace("원", "").strip();
        return v.matches("-?\\d+(\\.\\d+)?") ? Optional.of(new BigDecimal(v)) : Optional.empty();
    }
}
