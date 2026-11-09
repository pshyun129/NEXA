package com.ssafy.leaveservice.domain.policy.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.util.ArrayList;
import java.util.List;

public class LeaveAccrualRuleReplaceRequestDeserializer extends ValueDeserializer<LeaveAccrualRuleReplaceRequest> {

    @Override
    public LeaveAccrualRuleReplaceRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode request = context.readTree(parser);
        if (!request.isObject()) {
            return context.reportInputMismatch(LeaveAccrualRuleReplaceRequest.class, "요청은 JSON 객체여야 합니다.");
        }
        JsonNode ruleNodes = request.get("rules");
        if (ruleNodes == null || ruleNodes.isNull()) {
            return new LeaveAccrualRuleReplaceRequest(null);
        }
        if (!ruleNodes.isArray()) {
            return context.reportInputMismatch(LeaveAccrualRuleReplaceRequest.class, "rules는 배열이어야 합니다.");
        }

        List<LeaveAccrualRuleReplaceRequest.Rule> rules = new ArrayList<>();
        for (JsonNode ruleNode : ruleNodes) {
            if (ruleNode.isNull()) {
                rules.add(null);
                continue;
            }
            if (!ruleNode.isObject()) {
                return context.reportInputMismatch(LeaveAccrualRuleReplaceRequest.class, "규칙 항목은 JSON 객체여야 합니다.");
            }
            validateFieldTypes(ruleNode, context);
            LeaveAccrualRuleReplaceRequest.Rule rule = context.readTreeAsValue(ruleNode, LeaveAccrualRuleReplaceRequest.Rule.class);
            rules.add(rule);
        }
        return new LeaveAccrualRuleReplaceRequest(rules);
    }

    private void validateFieldTypes(JsonNode rule, DeserializationContext context) {
        for (String field : List.of("ruleId", "minServiceMonths", "maxServiceMonths", "grantMinutes")) {
            JsonNode value = rule.get(field);
            if (value != null && !value.isNull() && !value.isIntegralNumber()) {
                context.reportInputMismatch(LeaveAccrualRuleReplaceRequest.class, "%s는 정수여야 합니다.", field);
            }
        }
        JsonNode frequency = rule.get("frequency");
        if (frequency != null && !frequency.isNull() && !frequency.isString()) {
            context.reportInputMismatch(LeaveAccrualRuleReplaceRequest.class, "frequency는 문자열이어야 합니다.");
        }
        JsonNode active = rule.get("isActive");
        if (active != null && !active.isNull() && !active.isBoolean()) {
            context.reportInputMismatch(LeaveAccrualRuleReplaceRequest.class, "isActive는 Boolean이어야 합니다.");
        }
    }
}
