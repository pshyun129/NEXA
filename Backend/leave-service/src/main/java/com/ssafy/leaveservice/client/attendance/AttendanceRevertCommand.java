package com.ssafy.leaveservice.client.attendance;

import java.util.UUID;

/** Leave 내부 모델. 외부 요청 Body에는 cancelRequestId만 매핑한다. */
public record AttendanceRevertCommand(
        Long requestId, Long employeeId, Long documentId, UUID cancelRequestId, Long administratorId, String reason
) {
    public String idempotencyKey() { return "leave:admin-cancel:request:" + requestId; }
}
