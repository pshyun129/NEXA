package com.ssafy.leaveservice.client.attendance;

public interface AttendanceRevertClient {
    boolean isAvailable();
    AttendanceRevertResult revert(AttendanceRevertCommand command);

    // 조회 URI는 미확정. 멱등성이 검증된 구현체만 같은 UUID의 재요청으로 복구할 수 있다.
    default AttendanceRevertResult recover(AttendanceRevertCommand command) {
        return AttendanceRevertResult.unknown(command);
    }
}
