package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.client.attendance.HolidayClient.HolidayResult;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewRequest;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class LeaveRequestPreviewSnapshotService {
    private final LeaveRequestValidationService validationService;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LeaveRequestPreviewResponse preview(
            LeaveRequestPreviewRequest request,
            OffsetDateTime asOf,
            HolidayResult holidays
    ) {
        // Preview의 읽기 전용 스냅샷에서 공통 검증 수행
        return validationService.validate(request, asOf, holidays);
    }
}
