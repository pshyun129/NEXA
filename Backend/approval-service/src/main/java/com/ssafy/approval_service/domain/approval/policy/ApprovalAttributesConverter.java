package com.ssafy.approval_service.domain.approval.policy;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.json.JsonMapper;

/** 신청 값(ApprovalAttributes) ↔ JSON 문자열 (approval_document.attributes). 값이 없으면 NULL 로 저장 */
@Converter
public class ApprovalAttributesConverter implements AttributeConverter<ApprovalAttributes, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(ApprovalAttributes attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        return JSON.writeValueAsString(attributes);
    }

    @Override
    public ApprovalAttributes convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JSON.readValue(json, ApprovalAttributes.class);
    }
}
