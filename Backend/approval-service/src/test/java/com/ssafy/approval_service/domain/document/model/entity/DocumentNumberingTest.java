package com.ssafy.approval_service.domain.document.model.entity;

import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 문서번호 연도는 서버 시간대와 상관없이 한국 시간 기준 */
class DocumentNumberingTest {

    private static Document draftedAt(String isoDateTime) {
        Document document = Document.builder().formType(FormType.GENERAL).title("t").drafterId(1L).build();
        ReflectionTestUtils.setField(document, "draftedAt", OffsetDateTime.parse(isoDateTime));
        return document;
    }

    @Test
    void UTC로_전년도여도_한국_시간으로_새해면_새해_번호() {
        // UTC 2026-12-31 16:00 = 한국 시간 2027-01-01 01:00
        Document document = draftedAt("2026-12-31T16:00:00Z");

        document.assignDocNo(1);

        assertThat(document.numberingYear()).isEqualTo(2027);
        assertThat(document.getDocNo()).isEqualTo("GENERAL-2027-0001");
    }

    @Test
    void 한국_시간으로_12월_31일이면_그해_번호() {
        // UTC 2026-12-31 14:59 = 한국 시간 2026-12-31 23:59
        assertThat(draftedAt("2026-12-31T14:59:00Z").numberingYear()).isEqualTo(2026);
    }

    @Test
    void 문서번호는_한_번만_정할_수_있다() {
        Document document = draftedAt("2026-03-01T00:00:00+09:00");
        document.assignDocNo(3);

        assertThatThrownBy(() -> document.assignDocNo(4)).isInstanceOf(IllegalStateException.class);
        assertThat(document.getDocNo()).isEqualTo("GENERAL-2026-0003");
    }
}
