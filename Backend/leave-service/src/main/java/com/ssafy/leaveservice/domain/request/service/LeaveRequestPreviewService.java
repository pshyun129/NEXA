package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.client.attendance.HolidayClient;
import com.ssafy.leaveservice.client.attendance.HolidayClient.HolidayResult;
import com.ssafy.leaveservice.domain.request.dto.*;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class LeaveRequestPreviewService {
    private final EmployeeAccess employeeAccess;
    private final Validator validator;
    private final Clock leavePreviewClock;
    private final HolidayClient holidayClient;
    private final LeaveRequestPreviewSnapshotService snapshotService;

    public LeaveRequestPreviewResponse preview(LeaveRequestPreviewRequest request) {
        // 외부 호출 전에 본인 인증 및 입력 형식 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        OffsetDateTime asOf = OffsetDateTime.now(leavePreviewClock).truncatedTo(ChronoUnit.MICROS);
        LocalDate startDate = request.dates().stream().map(LeaveRequestPreviewRequest.DateRequest::leaveDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate endDate = request.dates().stream().map(LeaveRequestPreviewRequest.DateRequest::leaveDate).max(LocalDate::compareTo).orElseThrow();
        // PostgreSQL TIMESTAMPTZ로 조회할 수 없는 날짜는 DB 호출 전에 거부한다.
        boolean outsideDatabaseRange = request.dates().stream().anyMatch(date ->
                date.leaveDate().getYear() < -4712 || date.leaveDate().getYear() > 294275);
        if (outsideDatabaseRange) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // DB 트랜잭션을 시작하기 전에 공휴일 범위를 한 번만 조회
        HolidayResult holidays = holidayClient.findHolidays(startDate, endDate);

        // 로컬 정책·신청·잔액은 동일한 읽기 전용 스냅샷으로 검증
        return snapshotService.preview(request, asOf, holidays);
    }
}
