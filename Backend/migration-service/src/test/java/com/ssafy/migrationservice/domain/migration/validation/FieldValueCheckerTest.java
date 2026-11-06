package com.ssafy.migrationservice.domain.migration.validation;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.validation.issue.IssueCode;
import com.ssafy.migrationservice.domain.migration.validation.issue.Severity;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchemas;
import com.ssafy.migrationservice.domain.migration.validation.schema.FieldSpec;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FieldValueCheckerTest {

    private static FieldSpec employee(String name) {
        return EntitySchemas.of(MigrationEntity.HR_EMPLOYEE).orElseThrow().field(name).orElseThrow();
    }

    @Test
    void ISO_날짜는_통과() {
        assertThat(FieldValueChecker.check(employee("hireDate"), "2024-01-02")).isEmpty();
    }

    @Test
    void 다른_날짜_표기는_FIX와_바꿀_값을_준다() {
        var dot = FieldValueChecker.check(employee("hireDate"), "2023.3.15").orElseThrow();
        assertThat(dot.severity()).isEqualTo(Severity.FIX);
        assertThat(dot.code()).isEqualTo(IssueCode.DATE_FORMAT);
        assertThat(dot.suggestion()).isEqualTo("2023-03-15");

        assertThat(FieldValueChecker.check(employee("hireDate"), "20250701").orElseThrow().suggestion())
                .isEqualTo("2025-07-01");
        assertThat(FieldValueChecker.check(employee("hireDate"), "2024. 1. 2.").orElseThrow().suggestion())
                .isEqualTo("2024-01-02");
        assertThat(FieldValueChecker.check(employee("hireDate"), "2015년 3월 2일").orElseThrow().suggestion())
                .isEqualTo("2015-03-02");
    }

    @Test
    void 두_자리_연도와_엑셀_날짜_일련번호는_어떻게_읽었는지_알려준다() {
        var yy = FieldValueChecker.check(employee("hireDate"), "24-03-02").orElseThrow();
        assertThat(yy.suggestion()).isEqualTo("2024-03-02");
        assertThat(yy.message()).contains("두 자리를 연도로");
        assertThat(FieldValueChecker.check(employee("birthDate"), "85.11.3").orElseThrow().suggestion()).isEqualTo("1985-11-03");

        var serial = FieldValueChecker.check(employee("hireDate"), "33169").orElseThrow();
        assertThat(serial.suggestion()).isEqualTo("1990-10-23");
        assertThat(serial.message()).contains("일련번호");
        // 다섯 자리가 아닌 숫자(사번 등)는 날짜로 보지 않는다
        assertThat(FieldValueChecker.parseDate("123")).isEmpty();
        assertThat(FieldValueChecker.parseDate("123456")).isEmpty();
    }

    @Test
    void 없는_날짜와_날짜_아닌_값은_BLOCK() {
        assertThat(FieldValueChecker.check(employee("hireDate"), "2023-02-30").orElseThrow().code())
                .isEqualTo(IssueCode.INVALID_DATE);
        assertThat(FieldValueChecker.check(employee("hireDate"), "미정").orElseThrow().severity())
                .isEqualTo(Severity.BLOCK);
    }

    @Test
    void 이메일_전화_코드값() {
        assertThat(FieldValueChecker.check(employee("email"), "hong@nexa.com")).isEmpty();
        assertThat(FieldValueChecker.check(employee("email"), "hong.nexa.com").orElseThrow().code())
                .isEqualTo(IssueCode.INVALID_EMAIL);

        assertThat(FieldValueChecker.check(employee("mobile"), "010-1234-5678")).isEmpty();
        assertThat(FieldValueChecker.check(employee("mobile"), "1234").orElseThrow().severity())
                .isEqualTo(Severity.WARN);

        assertThat(FieldValueChecker.check(employee("empType"), "정규직")).isEmpty();
        assertThat(FieldValueChecker.check(employee("empType"), "regular")).isEmpty();
        assertThat(FieldValueChecker.check(employee("empType"), "인턴").orElseThrow().code())
                .isEqualTo(IssueCode.INVALID_ENUM);
    }

    @Test
    void 쉼표_숫자는_FIX() {
        FieldSpec sortOrder = EntitySchemas.of(MigrationEntity.HR_ORG).orElseThrow().field("sortOrder").orElseThrow();
        assertThat(FieldValueChecker.check(sortOrder, "1,000").orElseThrow().suggestion()).isEqualTo("1000");
        assertThat(FieldValueChecker.check(sortOrder, "첫번째").orElseThrow().code()).isEqualTo(IssueCode.INVALID_NUMBER);
    }

    @Test
    void parseDate() {
        assertThat(FieldValueChecker.parseDate("2024.1.2")).contains(LocalDate.of(2024, 1, 2));
        assertThat(FieldValueChecker.parseDate("00123")).isEmpty();
    }

    @Test
    void 허용_값의_앞부분이면_그_값을_제안한다() {
        assertThat(FieldValueChecker.enumPrefixMatch(employee("empType"), "정규")).contains("정규직");
        assertThat(FieldValueChecker.enumPrefixMatch(employee("empType"), "계약")).contains("계약직");
        assertThat(FieldValueChecker.enumPrefixMatch(employee("empType"), "파트")).contains("파트타임");
        assertThat(FieldValueChecker.enumPrefixMatch(employee("status"), "퇴")).isEmpty();      // 한 글자는 보지 않음
        assertThat(FieldValueChecker.enumPrefixMatch(employee("empType"), "인턴")).isEmpty();
    }

    @Test
    void 앞자리_0이_빠진_휴대전화는_고친_번호를_제안한다() {
        var dropped = FieldValueChecker.check(employee("mobile"), "1022710888").orElseThrow();
        assertThat(dropped.severity()).isEqualTo(Severity.FIX);
        assertThat(dropped.code()).isEqualTo(IssueCode.PHONE_ZERO_DROPPED);
        assertThat(dropped.suggestion()).isEqualTo("010-2271-0888");
        assertThat(FieldValueChecker.check(employee("mobile"), "162345678").orElseThrow().suggestion()).isEqualTo("016-234-5678");
        // 다른 모양은 그대로 확인 필요
        assertThat(FieldValueChecker.check(employee("mobile"), "12345").orElseThrow().code()).isEqualTo(IssueCode.INVALID_PHONE);
        assertThat(FieldValueChecker.check(employee("mobile"), "010-2271-0888")).isEmpty();
    }
}
