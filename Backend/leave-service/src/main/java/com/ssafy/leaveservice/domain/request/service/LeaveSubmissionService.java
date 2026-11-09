package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.client.attendance.HolidayClient;
import com.ssafy.leaveservice.domain.request.dto.*;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaveSubmissionService {
    private final EmployeeAccess employeeAccess;
    private final Validator validator;
    private final SubmissionPayloadHash payloadHash;
    private final HolidayClient holidayClient;
    private final LeaveSubmissionTransactionService transactionService;

    @Transactional(propagation = Propagation.NEVER)
    public LeaveSubmissionResult submit(String submissionKey, LeaveRequestPreviewRequest request) {
        // 외부 호출 전에 본인 인증과 요청 형식 확인
        Long employeeId = employeeAccess.currentEmployeeId();
        if (employeeId == null || employeeId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (request == null || !validator.validate(request).isEmpty()
                || submissionKey == null || !submissionKey.matches("[!-~]{1,128}")
                || request.dates().stream().anyMatch(date -> date.leaveDate().getYear() < -4712
                    || date.leaveDate().getYear() > 294275)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        String hash = payloadHash.calculate(request);

        // 확정된 기존 요청은 공휴일 장애나 시간 경과에 영향받지 않고 그대로 반환
        Optional<LeaveSubmissionResult> existing = transactionService.findExisting(submissionKey, hash);
        if (existing.isPresent()) {
            return existing.get();
        }

        // HTTP 호출은 쓰기 트랜잭션과 직원 잠금 밖에서 한 번만 수행
        LocalDate start = request.dates().stream().map(LeaveRequestPreviewRequest.DateRequest::leaveDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate end = request.dates().stream().map(LeaveRequestPreviewRequest.DateRequest::leaveDate).max(LocalDate::compareTo).orElseThrow();
        HolidayClient.HolidayResult holidays = holidayClient.findHolidays(start, end);
        if (!holidays.verified()) {
            throw new BusinessException(ErrorCode.HOLIDAY_VERIFICATION_UNAVAILABLE);
        }

        // 주말·공휴일은 쓰기 잠금을 얻기 전에 공통 순수 검증으로 거부
        for (LeaveRequestPreviewRequest.DateRequest date : request.dates()) {
            List<LeaveRequestPreviewResponse.Reason> reasons =
                    LeaveRequestCalendarValidation.validateDate(date.leaveDate(), holidays);
            if (!reasons.isEmpty()) {
                throw new BusinessException(ErrorCode.LEAVE_REQUEST_NOT_ALLOWED, reasons.getFirst().message());
            }
        }

        // 별도 프록시의 로컬 T1이 커밋된 뒤에만 내부 결과 반환
        return transactionService.submit(submissionKey, hash, request, holidays);
    }
}
