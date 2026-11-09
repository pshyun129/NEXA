package com.ssafy.leaveservice.domain.policy.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

public class LeavePolicyActivationRequestDeserializer extends ValueDeserializer<LeavePolicyActivationRequest> {

    @Override
    public LeavePolicyActivationRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode request = context.readTree(parser);
        if (!request.isObject()) {
            return context.reportInputMismatch(LeavePolicyActivationRequest.class, "요청은 JSON 객체여야 합니다.");
        }

        JsonNode versionId = request.get("versionId");
        if (versionId != null && !versionId.isNull()
                && (!versionId.isIntegralNumber() || !versionId.canConvertToLong())) {
            return context.reportInputMismatch(LeavePolicyActivationRequest.class, "versionId는 Long 정수여야 합니다.");
        }
        Long requestedVersionId = context.readTreeAsValue(versionId, Long.class);
        return new LeavePolicyActivationRequest(requestedVersionId);
    }
}
