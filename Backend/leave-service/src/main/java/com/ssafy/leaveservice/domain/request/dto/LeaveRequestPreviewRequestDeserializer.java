package com.ssafy.leaveservice.domain.request.dto;

import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.*;
import java.time.LocalDate;
import java.util.*;

public class LeaveRequestPreviewRequestDeserializer extends ValueDeserializer<LeaveRequestPreviewRequest> {
    @Override
    public LeaveRequestPreviewRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode request = context.readTree(parser);
        requireFields(request, Set.of("leaveTypeId", "reason", "dates"), context);
        JsonNode typeId = request.get("leaveTypeId");
        JsonNode reason = request.get("reason");
        JsonNode dates = request.get("dates");
        if ((typeId != null && !typeId.isNull() && (!typeId.isIntegralNumber() || !typeId.canConvertToLong()))
                || (reason != null && !reason.isNull() && !reason.isString())
                || (dates != null && !dates.isNull() && !dates.isArray())) {
            return context.reportInputMismatch(LeaveRequestPreviewRequest.class, "요청 필드 형식이 올바르지 않습니다.");
        }
        List<LeaveRequestPreviewRequest.DateRequest> dateRequests = null;
        if (dates != null && dates.isArray()) {
            dateRequests = new ArrayList<>();
            for (JsonNode date : dates) {
                requireFields(date, Set.of("leaveDate", "portion"), context);
                for (String field : List.of("leaveDate", "portion")) {
                    JsonNode value = date.get(field);
                    if (value != null && !value.isNull() && !value.isString()) {
                        return context.reportInputMismatch(LeaveRequestPreviewRequest.class, "날짜와 단위는 문자열이어야 합니다.");
                    }
                }
                dateRequests.add(new LeaveRequestPreviewRequest.DateRequest(
                        context.readTreeAsValue(date.get("leaveDate"), LocalDate.class),
                        context.readTreeAsValue(date.get("portion"), LeaveRequestPortion.class)
                ));
            }
        }
        return new LeaveRequestPreviewRequest(
                typeId == null || typeId.isNull() ? null : typeId.asLong(),
                reason == null || reason.isNull() ? null : reason.asString(),
                dateRequests
        );
    }

    private void requireFields(JsonNode value, Set<String> allowed, DeserializationContext context) {
        if (value == null || !value.isObject() || !allowed.containsAll(value.propertyNames())) {
            context.reportInputMismatch(LeaveRequestPreviewRequest.class, "허용된 요청 필드만 입력할 수 있습니다.");
        }
    }
}
