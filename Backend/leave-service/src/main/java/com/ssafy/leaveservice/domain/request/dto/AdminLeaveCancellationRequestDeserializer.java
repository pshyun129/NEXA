package com.ssafy.leaveservice.domain.request.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.util.Set;

public class AdminLeaveCancellationRequestDeserializer extends ValueDeserializer<AdminLeaveCancellationRequest> {

    @Override
    public AdminLeaveCancellationRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode request = context.readTree(parser);
        if (!request.isObject() || !Set.of("reason").containsAll(request.propertyNames())) {
            return context.reportInputMismatch(AdminLeaveCancellationRequest.class, "취소 사유만 입력할 수 있습니다.");
        }
        JsonNode reason = request.get("reason");
        if (reason != null && !reason.isNull() && !reason.isString()) {
            return context.reportInputMismatch(AdminLeaveCancellationRequest.class, "취소 사유는 문자열이어야 합니다.");
        }
        return new AdminLeaveCancellationRequest(reason == null || reason.isNull() ? null : reason.asString());
    }
}
