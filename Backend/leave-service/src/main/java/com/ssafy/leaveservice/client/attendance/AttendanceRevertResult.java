package com.ssafy.leaveservice.client.attendance;

import java.util.UUID;

public record AttendanceRevertResult(Long requestId, UUID cancelRequestId, Outcome outcome) {
    public enum Outcome { REVERTED_CONFIRMED, NOT_REVERTED_CONFIRMED, ACCEPTED, UNKNOWN }

    public static AttendanceRevertResult unknown(AttendanceRevertCommand command) {
        return new AttendanceRevertResult(command.requestId(), command.cancelRequestId(), Outcome.UNKNOWN);
    }
}
