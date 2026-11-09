package com.ssafy.approval_service.domain.approval.policy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 신청 값 저장 형식: 보낸 값만 JSON 으로, 값이 없으면 NULL */
class ApprovalAttributesConverterTest {

    private final ApprovalAttributesConverter converter = new ApprovalAttributesConverter();

    @Test
    void 보낸_값만_저장하고_다시_읽으면_같다() {
        ApprovalAttributes attributes = new ApprovalAttributes(new BigDecimal("4.5"), null, null, true, 2_000_000L);

        String json = converter.convertToDatabaseColumn(attributes);

        assertThat(json).isEqualTo("{\"days\":4.5,\"overseas\":true,\"amount\":2000000}");
        assertThat(converter.convertToEntityAttribute(json)).isEqualTo(attributes);
    }

    @Test
    void 값이_없으면_NULL() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(ApprovalAttributes.EMPTY)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void 예전에_저장된_empty_필드가_있어도_읽는다() {
        String legacy = "{\"days\":null,\"overtimeMinutes\":null,\"monthlyOvertimeMinutes\":null,"
                + "\"overseas\":null,\"amount\":2000000,\"empty\":false}";

        assertThat(converter.convertToEntityAttribute(legacy))
                .isEqualTo(new ApprovalAttributes(null, null, null, null, 2_000_000L));
    }
}
