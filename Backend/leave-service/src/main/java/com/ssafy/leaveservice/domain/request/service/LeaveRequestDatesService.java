package com.ssafy.leaveservice.domain.request.service;

import com.ssafy.leaveservice.domain.request.dto.LeaveRequestDatesResponse;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestDatesResponse.LeaveDateItem;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository.RequestDateRow;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaveRequestDatesService {
    private final LeaveRequestRepository requestRepository;

    @Transactional(readOnly = true)
    public LeaveRequestDatesResponse getDates(Long requestId) {
        if (requestId == null || requestId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 신청에 연결된 휴가 유형과 저장된 날짜를 한 번에 조회
        List<RequestDateRow> dateRows = requestRepository.findRequestDates(requestId);
        if (dateRows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        RequestDateRow firstRow = dateRows.getFirst();
        // 취소된 신청은 근태에 휴가로 남으면 안 되므로 없는 신청과 같이 404 (늦게 처리된 승인 이벤트가 휴가를 되살리지 않게)
        if (firstRow.getStatus() == LeaveRequestStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String leaveTypeName = firstRow.getLeaveTypeName();
        Boolean paid = firstRow.getPaid();
        if (firstRow.getLeaveDate() == null || leaveTypeName == null || paid == null) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }

        // 저장된 서울 기준 휴가 일자와 신청 단위를 응답으로 변환
        List<LeaveDateItem> dates = new ArrayList<>(dateRows.size());
        for (RequestDateRow dateRow : dateRows) {
            LocalDate leaveDate = dateRow.getLeaveDate().atZoneSameInstant(PreviewGrantAllocator.SEOUL).toLocalDate();
            dates.add(new LeaveDateItem(leaveDate, dateRow.getPortion()));
        }
        return new LeaveRequestDatesResponse(requestId, firstRow.getEmployeeId(), leaveTypeName, paid, dates);
    }
}
