package com.ssafy.attendanceservice.domain.attendance.controller;

import com.ssafy.attendanceservice.domain.attendance.dto.LeaveRevertRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.LeaveRevertResponse;
import com.ssafy.attendanceservice.domain.attendance.service.LeaveService;
import com.ssafy.attendanceservice.global.dto.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 휴가 서비스 연동용 내부 API (서비스 간 호출, 토큰 없음). 게이트웨이에서는 /api/v1/attn/internal/** 를 외부에 열지 않는다.
 * 승인된 휴가를 휴가 서비스에서 관리자가 취소하면(결재 서비스를 거치지 않음) 근태의 휴가를 지우고 정상 근무 기준으로 되돌린다
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/attn/internal/leave-requests")
public class InternalLeaveController {

    private final LeaveService leaveService;

    // 휴가 원복 API (여러 번 불러도 결과가 같다: 이미 원복했으면 reverted=false)
    @PostMapping("/{leaveRequestId}/revert")
    public ResponseEntity<ApiResult<LeaveRevertResponse>> revert(
            @PathVariable Long leaveRequestId,
            @Valid @RequestBody LeaveRevertRequest request) {
        List<LocalDate> revertedDates = leaveService.cancelLeave(leaveRequestId);
        log.info("휴가 원복: leaveReqId={}, cancelRequestId={}, revertedDates={}",
                leaveRequestId, request.cancelRequestId(), revertedDates);
        return ResponseEntity.ok(ApiResult.success(
                LeaveRevertResponse.of(leaveRequestId, request.cancelRequestId(), revertedDates)));
    }
}
