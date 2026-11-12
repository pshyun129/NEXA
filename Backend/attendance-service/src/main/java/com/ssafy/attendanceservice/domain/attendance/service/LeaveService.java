package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.ApprovedLeave;

import java.time.LocalDate;
import java.util.List;

public interface LeaveService {

    /**
     * 승인된 휴가 저장 (휴가 결재 승인 이벤트 수신 시 호출).
     * 같은 휴가 신청을 이미 저장했으면 무시한다 (같은 이벤트가 두 번 와도 결과가 같다).
     * 이미 근태 기록이 있는 날은 휴가를 반영해 근태이상을 다시 판정한다
     */
    void applyApprovedLeave(ApprovedLeave leave);

    /**
     * 휴가 취소(결재 회수, 승인 후 관리자 취소). 저장한 휴가를 지우고, 근태 기록이 있는 날은 다시 판정한다.
     * 지운 날짜를 돌려준다. 없는 휴가(이미 취소했거나 저장한 적 없음)면 빈 목록 (여러 번 불러도 결과가 같다)
     */
    List<LocalDate> cancelLeave(Long leaveReqId);
}
