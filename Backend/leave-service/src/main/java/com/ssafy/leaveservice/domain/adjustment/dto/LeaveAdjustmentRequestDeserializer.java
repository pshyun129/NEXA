package com.ssafy.leaveservice.domain.adjustment.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.util.Set;

public class LeaveAdjustmentRequestDeserializer extends ValueDeserializer<LeaveAdjustmentRequest> {

    @Override
    public LeaveAdjustmentRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode request = context.readTree(parser);
        Set<String> allowedFields = Set.of("accountId", "grantId", "quantityDelta", "reason");
        if (!request.isObject() || !allowedFields.containsAll(request.propertyNames())) {
            return context.reportInputMismatch(LeaveAdjustmentRequest.class, "허용된 요청 필드만 입력할 수 있습니다.");
        }

        for (String field : Set.of("accountId", "grantId", "quantityDelta")) {
            JsonNode value = request.get(field);
            if (value != null && !value.isNull()
                    && (!value.isIntegralNumber() || !value.canConvertToLong()
                    || (field.equals("quantityDelta") && !value.canConvertToInt()))) {
                return context.reportInputMismatch(LeaveAdjustmentRequest.class, "ID와 조정량은 범위 내 정수여야 합니다.");
            }
        }

        JsonNode reason = request.get("reason");
        if (reason != null && !reason.isNull() && !reason.isString()) {
            return context.reportInputMismatch(LeaveAdjustmentRequest.class, "조정 사유는 문자열이어야 합니다.");
        }

        JsonNode accountId = request.get("accountId");
        JsonNode grantId = request.get("grantId");
        JsonNode quantityDelta = request.get("quantityDelta");
        return new LeaveAdjustmentRequest(
                accountId == null || accountId.isNull() ? null : accountId.asLong(),
                grantId == null || grantId.isNull() ? null : grantId.asLong(),
                quantityDelta == null || quantityDelta.isNull() ? null : quantityDelta.asInt(),
                reason == null || reason.isNull() ? null : reason.asString()
        );
    }
}
