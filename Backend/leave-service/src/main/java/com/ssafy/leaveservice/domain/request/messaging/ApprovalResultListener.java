package com.ssafy.leaveservice.domain.request.messaging;

import com.ssafy.leaveservice.domain.request.service.LeaveApprovalSettlementService;
import com.ssafy.leaveservice.domain.request.service.LeaveApprovalSettlementService.Outcome;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RequiredArgsConstructor
class ApprovalResultListener {
    static final String TOPIC = "approval.document.result";

    private final JsonMapper jsonMapper;
    private final LeaveApprovalSettlementService settlementService;

    @KafkaListener(
            topics = TOPIC,
            groupId = "${leave.approval.results.group-id}",
            containerFactory = "approvalResultListenerFactory"
    )
    public void receive(String payload) {
        // 공유 토픽에서 다른 도메인을 제외한 뒤 휴가 이벤트 형식을 검증한다.
        ApprovalResultEvent event = parse(payload);
        if (event == null) {
            return;
        }

        // 트랜잭션 프록시가 커밋을 마친 뒤에만 리스너가 정상 반환한다.
        Outcome outcome;
        try {
            outcome = settlementService.settle(event.toDecision());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.INVALID_INPUT
                    || exception.getErrorCode() == ErrorCode.DATA_INTEGRITY_VIOLATION) {
                throw new InvalidResult("Invalid leave approval result: " + event.eventId());
            }
            throw exception;
        }
        switch (outcome) {
            case APPLIED, ALREADY_APPLIED -> { }
            case CONFLICT -> throw new InvalidResult("Conflicting leave approval result: " + event.eventId());
            case AWAITING_DOCUMENT_LINK, REQUEST_NOT_FOUND ->
                    throw new IllegalStateException("Leave approval result " + outcome + ": " + event.eventId());
        }
    }

    private ApprovalResultEvent parse(String payload) {
        try {
            JsonNode root = jsonMapper.readTree(payload);
            JsonNode sourceType = root == null ? null : root.get("sourceType");
            if (sourceType == null || !sourceType.isString() || sourceType.asString().isBlank()) {
                throw new InvalidResult("Missing approval result source");
            }
            if (!"LEAVE".equals(sourceType.asString())) {
                return null;
            }
            ApprovalResultEvent event = jsonMapper.treeToValue(root, ApprovalResultEvent.class);
            if (event.eventId() == null || event.occurredAt() == null) {
                throw new InvalidResult("Missing approval event identity or timestamp");
            }
            return event;
        } catch (JacksonException exception) {
            // 역직렬화 예외의 원본 JSON은 로그로 전달하지 않는다.
            throw new InvalidResult("Malformed approval result JSON");
        }
    }

    static class InvalidResult extends RuntimeException {
        InvalidResult(String message) {
            super(message);
        }
    }
}
