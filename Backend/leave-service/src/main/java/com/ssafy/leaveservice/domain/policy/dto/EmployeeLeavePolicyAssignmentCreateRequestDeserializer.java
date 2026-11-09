package com.ssafy.leaveservice.domain.policy.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.time.OffsetDateTime;
import java.util.List;

public class EmployeeLeavePolicyAssignmentCreateRequestDeserializer
        extends ValueDeserializer<EmployeeLeavePolicyAssignmentCreateRequest> {

    @Override
    public EmployeeLeavePolicyAssignmentCreateRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode request = context.readTree(parser);
        if (!request.isObject()) {
            return context.reportInputMismatch(EmployeeLeavePolicyAssignmentCreateRequest.class, "요청은 JSON 객체여야 합니다.");
        }
        JsonNode policyId = request.get("policyId");
        if (policyId != null && !policyId.isNull()
                && (!policyId.isIntegralNumber() || !policyId.canConvertToLong())) {
            return context.reportInputMismatch(EmployeeLeavePolicyAssignmentCreateRequest.class, "policyId는 Long 정수여야 합니다.");
        }
        for (String field : List.of("effectiveFrom", "effectiveTo")) {
            JsonNode timestamp = request.get(field);
            if (timestamp != null && !timestamp.isNull() && !timestamp.isString()) {
                return context.reportInputMismatch(EmployeeLeavePolicyAssignmentCreateRequest.class, "%s는 시각 문자열이어야 합니다.", field);
            }
        }
        Long requestedPolicyId = context.readTreeAsValue(policyId, Long.class);
        OffsetDateTime effectiveFrom = context.readTreeAsValue(request.get("effectiveFrom"), OffsetDateTime.class);
        OffsetDateTime effectiveTo = context.readTreeAsValue(request.get("effectiveTo"), OffsetDateTime.class);
        return new EmployeeLeavePolicyAssignmentCreateRequest(requestedPolicyId, effectiveFrom, effectiveTo);
    }
}
