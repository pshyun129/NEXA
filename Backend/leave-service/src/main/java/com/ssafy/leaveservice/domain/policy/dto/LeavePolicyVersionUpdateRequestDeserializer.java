package com.ssafy.leaveservice.domain.policy.dto;

import com.ssafy.leaveservice.domain.policy.entity.PolicyBaseType;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LeavePolicyVersionUpdateRequestDeserializer extends ValueDeserializer<LeavePolicyVersionUpdateRequest> {

    @Override
    public LeavePolicyVersionUpdateRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode request = context.readTree(parser);
        if (!request.isObject()) {
            return context.reportInputMismatch(LeavePolicyVersionUpdateRequest.class, "PATCH 요청은 JSON 객체여야 합니다.");
        }

        validateFieldTypes(request, context);

        // null도 전달된 필드로 보존하며 클라이언트가 전달한 suppliedFields는 사용하지 않는다.
        Set<String> suppliedFields = new HashSet<>();
        for (String fieldName : List.of("baseType", "fiscalStart", "unitMinutes", "carryOverAllowed",
                "carryOverLimitMinutes", "expiryRule", "effectiveFrom", "effectiveTo")) {
            if (request.has(fieldName)) {
                suppliedFields.add(fieldName);
            }
        }

        PolicyBaseType baseType = context.readTreeAsValue(request.get("baseType"), PolicyBaseType.class);
        String fiscalStart = context.readTreeAsValue(request.get("fiscalStart"), String.class);
        Integer unitMinutes = context.readTreeAsValue(request.get("unitMinutes"), Integer.class);
        Boolean carryOverAllowed = context.readTreeAsValue(request.get("carryOverAllowed"), Boolean.class);
        Integer carryOverLimitMinutes = context.readTreeAsValue(request.get("carryOverLimitMinutes"), Integer.class);
        String expiryRule = context.readTreeAsValue(request.get("expiryRule"), String.class);
        OffsetDateTime effectiveFrom = context.readTreeAsValue(request.get("effectiveFrom"), OffsetDateTime.class);
        OffsetDateTime effectiveTo = context.readTreeAsValue(request.get("effectiveTo"), OffsetDateTime.class);

        return new LeavePolicyVersionUpdateRequest(
                baseType,
                fiscalStart,
                unitMinutes,
                carryOverAllowed,
                carryOverLimitMinutes,
                expiryRule,
                effectiveFrom,
                effectiveTo,
                suppliedFields
        );
    }

    private void validateFieldTypes(JsonNode request, DeserializationContext context) {
        for (String fieldName : List.of("baseType", "fiscalStart", "expiryRule", "effectiveFrom", "effectiveTo")) {
            JsonNode value = request.get(fieldName);
            if (value != null && !value.isNull() && !value.isString()) {
                context.reportInputMismatch(LeavePolicyVersionUpdateRequest.class, "%s는 문자열이어야 합니다.", fieldName);
            }
        }

        JsonNode carryOverAllowed = request.get("carryOverAllowed");
        if (carryOverAllowed != null && !carryOverAllowed.isNull() && !carryOverAllowed.isBoolean()) {
            context.reportInputMismatch(LeavePolicyVersionUpdateRequest.class, "carryOverAllowed는 Boolean이어야 합니다.");
        }

        for (String fieldName : List.of("unitMinutes", "carryOverLimitMinutes")) {
            JsonNode value = request.get(fieldName);
            if (value != null && !value.isNull() && !value.isIntegralNumber()) {
                context.reportInputMismatch(LeavePolicyVersionUpdateRequest.class, "%s는 정수여야 합니다.", fieldName);
            }
        }
    }
}
