package com.ssafy.leaveservice.client.attendance;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 담당자가 제공한 Attendance 원복 응답 계약. 배포 환경 검증 후 기능을 활성화한다. */
public final class AttendanceRevertHttpClient implements AttendanceRevertClient {
    private final RestClient client;
    private final boolean idempotencyVerified;

    public AttendanceRevertHttpClient(RestClient client, boolean idempotencyVerified) {
        this.client = client;
        this.idempotencyVerified = idempotencyVerified;
    }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public AttendanceRevertResult revert(AttendanceRevertCommand command) {
        try {
            // 사용자 JWT 없이 내부 서비스 경로로 요청하고 성공 Body까지 검증한다.
            ResponseEntity<JsonNode> response = client.post()
                    .uri("/api/v1/attn/internal/leave-requests/{id}/revert", command.requestId())
                    .body(new RevertRequest(command.cancelRequestId())).retrieve().toEntity(JsonNode.class);
            if (response.getStatusCode().value() != 200) {
                return AttendanceRevertResult.unknown(command);
            }
            RevertResponse result = readResponse(response.getBody());
            if (result == null || !command.requestId().equals(result.leaveRequestId())
                    || !command.cancelRequestId().equals(result.cancelRequestId())) {
                return AttendanceRevertResult.unknown(command);
            }

            // false는 동일 신청·UUID의 멱등 완료이다. 빈 날짜 목록도 복원 실패가 아니다.
            return new AttendanceRevertResult(command.requestId(), command.cancelRequestId(),
                    AttendanceRevertResult.Outcome.REVERTED_CONFIRMED);
        } catch (RestClientException exception) {
            // 4xx/5xx와 통신 오류만으로 미원복을 확정하지 않는다. 외부 오류 본문도 노출하지 않는다.
            return AttendanceRevertResult.unknown(command);
        }
    }

    @Override
    public AttendanceRevertResult recover(AttendanceRevertCommand command) {
        return idempotencyVerified ? revert(command) : AttendanceRevertResult.unknown(command);
    }

    private RevertResponse readResponse(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        JsonNode requestId = body.get("leaveRequestId");
        JsonNode cancelId = body.get("cancelRequestId");
        JsonNode reverted = body.get("reverted");
        JsonNode dates = body.get("revertedDates");
        if (requestId == null || !requestId.isIntegralNumber() || !requestId.canConvertToLong()
                || cancelId == null || !cancelId.isString() || reverted == null || !reverted.isBoolean()
                || dates == null || !dates.isArray()) {
            return null;
        }
        try {
            UUID cancelRequestId = UUID.fromString(cancelId.asString());
            if (!cancelRequestId.toString().equalsIgnoreCase(cancelId.asString())) {
                return null;
            }
            List<LocalDate> revertedDates = new ArrayList<>();
            for (JsonNode date : dates) {
                if (!date.isString()) {
                    return null;
                }
                revertedDates.add(LocalDate.parse(date.asString()));
            }
            return new RevertResponse(requestId.asLong(), cancelRequestId, reverted.asBoolean(), revertedDates);
        } catch (IllegalArgumentException | DateTimeParseException invalidResponse) {
            return null;
        }
    }

    private record RevertRequest(UUID cancelRequestId) {}
    private record RevertResponse(Long leaveRequestId, UUID cancelRequestId, boolean reverted, List<LocalDate> revertedDates) {}
}
