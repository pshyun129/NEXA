package com.ssafy.leaveservice.domain.leavetype.dto;

import com.ssafy.leaveservice.domain.leavetype.entity.BalanceMode;
import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LeaveTypeUpdateRequestDeserializer extends ValueDeserializer<LeaveTypeUpdateRequest> {

    @Override
    public LeaveTypeUpdateRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode request = context.readTree(parser);
        if (!request.isObject()) {
            return context.reportInputMismatch(LeaveTypeUpdateRequest.class, "PATCH 요청은 JSON 객체여야 합니다.");
        }

        validateFieldTypes(request, context);

        // 값이 null이어도 필드가 전달됐다는 사실을 보존한다. 내부 suppliedFields 입력은 신뢰하지 않는다.
        Set<String> suppliedFields = new HashSet<>();
        List<String> fieldNames = List.of(
                "name", "isPaid", "balanceMode", "balanceTypeId", "requestUnit", "maxMinutes", "isActive"
        );
        for (String fieldName : fieldNames) {
            if (request.has(fieldName)) {
                suppliedFields.add(fieldName);
            }
        }

        String name = context.readTreeAsValue(request.get("name"), String.class);
        Boolean paid = context.readTreeAsValue(request.get("isPaid"), Boolean.class);
        BalanceMode balanceMode = context.readTreeAsValue(request.get("balanceMode"), BalanceMode.class);
        Long balanceTypeId = context.readTreeAsValue(request.get("balanceTypeId"), Long.class);
        RequestUnit requestUnit = context.readTreeAsValue(request.get("requestUnit"), RequestUnit.class);
        Integer maxMinutes = context.readTreeAsValue(request.get("maxMinutes"), Integer.class);
        Boolean active = context.readTreeAsValue(request.get("isActive"), Boolean.class);

        return new LeaveTypeUpdateRequest(
                name,
                paid,
                balanceMode,
                balanceTypeId,
                requestUnit,
                maxMinutes,
                active,
                suppliedFields
        );
    }

    private void validateFieldTypes(JsonNode request, DeserializationContext context) {
        // 숫자 Enum, 문자열 Boolean, 소수의 정수 변환으로 요청 의미가 달라지는 것을 방지한다.
        for (String fieldName : List.of("name", "balanceMode", "requestUnit")) {
            JsonNode value = request.get(fieldName);
            if (value != null && !value.isNull() && !value.isString()) {
                context.reportInputMismatch(LeaveTypeUpdateRequest.class, "%s는 문자열이어야 합니다.", fieldName);
            }
        }

        for (String fieldName : List.of("isPaid", "isActive")) {
            JsonNode value = request.get(fieldName);
            if (value != null && !value.isNull() && !value.isBoolean()) {
                context.reportInputMismatch(LeaveTypeUpdateRequest.class, "%s는 Boolean이어야 합니다.", fieldName);
            }
        }

        for (String fieldName : List.of("balanceTypeId", "maxMinutes")) {
            JsonNode value = request.get(fieldName);
            if (value != null && !value.isNull() && !value.isIntegralNumber()) {
                context.reportInputMismatch(LeaveTypeUpdateRequest.class, "%s는 정수여야 합니다.", fieldName);
            }
        }
    }
}
