package com.ssafy.leaveservice.domain.leavetype;

import com.ssafy.leaveservice.domain.leavetype.dto.LeaveTypeUpdateRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

class LeaveTypeUpdateRequestTest {

    private static final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
    private final Validator validator = validatorFactory.getValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void distinguishesOmittedFieldsFromExplicitNull() {
        LeaveTypeUpdateRequest omitted = objectMapper.readValue("{}", LeaveTypeUpdateRequest.class);
        LeaveTypeUpdateRequest cleared = objectMapper.readValue(
                "{\"balanceTypeId\":null,\"maxMinutes\":null}", LeaveTypeUpdateRequest.class
        );

        assertThat(omitted.hasField("balanceTypeId")).isFalse();
        assertThat(omitted.hasField("maxMinutes")).isFalse();
        assertThat(cleared.hasField("balanceTypeId")).isTrue();
        assertThat(cleared.hasField("maxMinutes")).isTrue();
        assertThat(cleared.balanceTypeId()).isNull();
        assertThat(cleared.maxMinutes()).isNull();
        assertThat(validator.validate(omitted)).isNotEmpty();
        assertThat(validator.validate(cleared)).isEmpty();
    }

    @Test
    void preservesExplicitFalseAndCannotForgeFieldPresence() {
        LeaveTypeUpdateRequest request = objectMapper.readValue("""
                {"isPaid":false,"isActive":false,"suppliedFields":["maxMinutes"]}
                """, LeaveTypeUpdateRequest.class);

        assertThat(request.isPaid()).isFalse();
        assertThat(request.isActive()).isFalse();
        assertThat(request.hasField("maxMinutes")).isFalse();
        assertThat(request.suppliedFields()).containsExactlyInAnyOrder("isPaid", "isActive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "isPaid", "balanceMode", "requestUnit", "isActive"})
    void rejectsNullForNonNullableFields(String fieldName) {
        LeaveTypeUpdateRequest request = objectMapper.readValue(
                "{\"" + fieldName + "\":null}", LeaveTypeUpdateRequest.class
        );
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankName(String name) {
        LeaveTypeUpdateRequest request = objectMapper.readValue(
                "{\"name\":\"" + name + "\"}", LeaveTypeUpdateRequest.class
        );
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    void rejectsNameLongerThanColumn() {
        LeaveTypeUpdateRequest request = objectMapper.readValue(
                "{\"name\":\"" + "가".repeat(101) + "\"}", LeaveTypeUpdateRequest.class
        );
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveMaxMinutes(int maxMinutes) {
        LeaveTypeUpdateRequest request = objectMapper.readValue(
                "{\"maxMinutes\":" + maxMinutes + "}", LeaveTypeUpdateRequest.class
        );
        assertThat(validator.validate(request)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"balanceMode", "requestUnit"})
    void rejectsInvalidEnum(String fieldName) {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"" + fieldName + "\":\"INVALID\"}", LeaveTypeUpdateRequest.class
        )).isInstanceOf(tools.jackson.core.JacksonException.class);
    }

    @Test
    void rejectsArrayRequest() {
        assertThatThrownBy(() -> objectMapper.readValue("[]", LeaveTypeUpdateRequest.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"balanceMode\":0}", "{\"requestUnit\":0}", "{\"name\":123}",
            "{\"isPaid\":\"true\"}", "{\"isActive\":1}",
            "{\"balanceTypeId\":1.5}", "{\"maxMinutes\":1.5}", "{\"maxMinutes\":\"30\"}"
    })
    void rejectsIncorrectJsonTypes(String json) {
        assertThatThrownBy(() -> objectMapper.readValue(json, LeaveTypeUpdateRequest.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }
}
