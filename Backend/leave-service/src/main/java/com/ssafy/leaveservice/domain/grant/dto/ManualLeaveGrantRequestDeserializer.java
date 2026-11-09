package com.ssafy.leaveservice.domain.grant.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

public class ManualLeaveGrantRequestDeserializer extends ValueDeserializer<ManualLeaveGrantRequest> {
    @Override
    public ManualLeaveGrantRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode request = context.readTree(parser);
        Set<String> fields = Set.of("balanceTypeId", "grantMode", "grantMinutes", "validFrom", "expiresOn", "reason");
        if (!request.isObject() || !fields.containsAll(request.propertyNames())) {
            return context.reportInputMismatch(ManualLeaveGrantRequest.class, "허용된 지급 필드만 입력할 수 있습니다.");
        }
        for (String field : fields) {
            JsonNode value = request.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            boolean numeric = field.equals("balanceTypeId") || field.equals("grantMinutes");
            if ((numeric && (!value.isIntegralNumber() || !value.canConvertToLong()
                    || (field.equals("grantMinutes") && !value.canConvertToInt())))
                    || (!numeric && !value.isString())) {
                return context.reportInputMismatch(ManualLeaveGrantRequest.class, "지급 입력값의 타입 또는 범위가 올바르지 않습니다.");
            }
        }
        try {
            JsonNode type = request.get("balanceTypeId");
            JsonNode minutes = request.get("grantMinutes");
            String mode = text(request, "grantMode");
            String validFrom = text(request, "validFrom");
            String expiresOn = text(request, "expiresOn");
            return new ManualLeaveGrantRequest(
                    type == null || type.isNull() ? null : type.asLong(),
                    mode == null ? null : ManualLeaveGrantRequest.GrantMode.valueOf(mode),
                    minutes == null || minutes.isNull() ? null : minutes.asInt(),
                    validFrom == null ? null : OffsetDateTime.parse(validFrom),
                    expiresOn == null ? null : OffsetDateTime.parse(expiresOn), text(request, "reason")
            );
        } catch (IllegalArgumentException | DateTimeParseException failure) {
            return context.reportInputMismatch(ManualLeaveGrantRequest.class, "지급 구분 또는 오프셋을 포함한 날짜·시간이 올바르지 않습니다.");
        }
    }

    private String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
