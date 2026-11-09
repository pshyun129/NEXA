package com.ssafy.leaveservice.domain.policy.dto;

import com.ssafy.leaveservice.domain.policy.entity.PolicyAllowedUnits;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.util.List;

public class LeavePolicyTypeRuleUpsertRequestDeserializer extends ValueDeserializer<LeavePolicyTypeRuleUpsertRequest> {

    @Override
    public LeavePolicyTypeRuleUpsertRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode request = context.readTree(parser);
        if (!request.isObject()) {
            return context.reportInputMismatch(LeavePolicyTypeRuleUpsertRequest.class, "요청은 JSON 객체여야 합니다.");
        }

        JsonNode requestable = request.get("isRequestable");
        if (requestable != null && !requestable.isNull() && !requestable.isBoolean()) {
            return context.reportInputMismatch(LeavePolicyTypeRuleUpsertRequest.class, "isRequestable은 Boolean이어야 합니다.");
        }
        JsonNode allowedUnits = request.get("allowedUnits");
        if (allowedUnits != null && !allowedUnits.isNull() && !allowedUnits.isString()) {
            return context.reportInputMismatch(LeavePolicyTypeRuleUpsertRequest.class, "allowedUnits는 문자열이어야 합니다.");
        }
        for (String field : List.of("minMinutes", "maxMinutes")) {
            JsonNode value = request.get(field);
            if (value != null && !value.isNull() && !value.isIntegralNumber()) {
                return context.reportInputMismatch(LeavePolicyTypeRuleUpsertRequest.class, "%s는 정수여야 합니다.", field);
            }
        }

        Boolean isRequestable = context.readTreeAsValue(requestable, Boolean.class);
        PolicyAllowedUnits units = context.readTreeAsValue(allowedUnits, PolicyAllowedUnits.class);
        Integer minMinutes = context.readTreeAsValue(request.get("minMinutes"), Integer.class);
        Integer maxMinutes = context.readTreeAsValue(request.get("maxMinutes"), Integer.class);
        return new LeavePolicyTypeRuleUpsertRequest(isRequestable, units, minMinutes, maxMinutes);
    }
}
