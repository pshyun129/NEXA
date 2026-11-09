package com.ssafy.leaveservice.domain.adjustment;

import com.ssafy.leaveservice.domain.adjustment.dto.LeaveAdjustmentRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaveAdjustmentRequestTest {

    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "2147483648", "-2147483649", "\"120\"", "true", "{}", "[]"})
    void rejectsNonIntegerOrOutOfRangeDelta(String value) {
        String json = "{\"accountId\":1,\"grantId\":1,\"quantityDelta\":" + value + ",\"reason\":\"정정\"}";
        assertThatThrownBy(() -> mapper.readValue(json, LeaveAdjustmentRequest.class)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -240, 480, Integer.MAX_VALUE})
    void preservesSignedIntegerWithoutNegation(int delta) {
        LeaveAdjustmentRequest request = new LeaveAdjustmentRequest(1L, 1L, delta, " 정정 ");
        assertThat(factory.getValidator().validate(request)).isEmpty();
        assertThat(request.reason()).isEqualTo(" 정정 ");
        assertThat(request.quantityDelta()).isEqualTo(delta);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    void rejectsMissingOrBlankReason(String reason) {
        assertThat(factory.getValidator().validate(new LeaveAdjustmentRequest(1L, 1L, 1, reason))).isNotEmpty();
    }

    @Test
    void validatesReasonLengthBoundary() {
        assertThat(factory.getValidator().validate(new LeaveAdjustmentRequest(1L, 1L, 1, "가".repeat(500)))).isEmpty();
        assertThat(factory.getValidator().validate(new LeaveAdjustmentRequest(1L, 1L, 1, "가".repeat(501)))).isNotEmpty();
    }

    @Test
    void rejectsZeroMissingDeltaAndNonPositiveIds() {
        for (LeaveAdjustmentRequest request : new LeaveAdjustmentRequest[] {
                new LeaveAdjustmentRequest(1L, 1L, 0, "정정"),
                new LeaveAdjustmentRequest(1L, 1L, null, "정정"),
                new LeaveAdjustmentRequest(null, 1L, 1, "정정"),
                new LeaveAdjustmentRequest(0L, 1L, 1, "정정"),
                new LeaveAdjustmentRequest(1L, -1L, 1, "정정")
        }) {
            assertThat(factory.getValidator().validate(request)).isNotEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"actorEmpId", "employeeId", "reservedDelta", "eventType"})
    void rejectsClientControlledAuditAndAccountingFields(String field) {
        String json = "{\"accountId\":1,\"grantId\":1,\"quantityDelta\":1,\"reason\":\"정정\",\"" + field + "\":10}";
        assertThatThrownBy(() -> mapper.readValue(json, LeaveAdjustmentRequest.class)).isInstanceOf(RuntimeException.class);
    }
}
