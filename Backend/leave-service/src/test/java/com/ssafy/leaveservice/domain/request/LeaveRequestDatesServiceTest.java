package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository.RequestDateRow;
import com.ssafy.leaveservice.domain.request.service.LeaveRequestDatesService;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LeaveRequestDatesServiceTest {
    private final LeaveRequestRepository repository = mock(LeaveRequestRepository.class);
    private final LeaveRequestDatesService service = new LeaveRequestDatesService(repository);

    @Test
    void returnsSeoulCalendarDateAndStoredHourlyPortion() {
        RequestDateRow row = mock(RequestDateRow.class);
        when(row.getEmployeeId()).thenReturn(7L);
        when(row.getLeaveDate()).thenReturn(OffsetDateTime.parse("2026-11-08T15:00:00Z"));
        when(row.getPortion()).thenReturn(LeaveRequestPortion.HOURLY);
        when(row.getLeaveTypeName()).thenReturn("무급휴가");
        when(row.getPaid()).thenReturn(false);
        when(repository.findRequestDates(42L)).thenReturn(List.of(row));

        var response = service.getDates(42L);
        assertThat(response.requestId()).isEqualTo(42L);
        assertThat(response.employeeId()).isEqualTo(7L);
        assertThat(response.leaveTypeName()).isEqualTo("무급휴가");
        assertThat(response.isPaid()).isFalse();
        assertThat(response.dates()).singleElement().satisfies(date -> {
            assertThat(date.leaveDate()).isEqualTo(LocalDate.of(2026, 11, 9));
            assertThat(date.portion()).isEqualTo(LeaveRequestPortion.HOURLY);
        });
    }

    @Test
    void cancelledRequestIsNotFound() {
        RequestDateRow row = mock(RequestDateRow.class);
        when(row.getStatus()).thenReturn(LeaveRequestStatus.CANCELLED);
        when(row.getLeaveDate()).thenReturn(OffsetDateTime.parse("2026-11-08T15:00:00Z"));
        when(row.getLeaveTypeName()).thenReturn("연차");
        when(row.getPaid()).thenReturn(true);
        when(repository.findRequestDates(42L)).thenReturn(List.of(row));

        assertThatThrownBy(() -> service.getDates(42L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void rejectsMissingPayStatus() {
        RequestDateRow row = mock(RequestDateRow.class);
        when(row.getLeaveDate()).thenReturn(OffsetDateTime.parse("2026-11-08T15:00:00Z"));
        when(row.getLeaveTypeName()).thenReturn("연차");
        when(row.getPaid()).thenReturn(null);
        when(repository.findRequestDates(42L)).thenReturn(List.of(row));

        assertThatThrownBy(() -> service.getDates(42L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DATA_INTEGRITY_VIOLATION));
    }

    @Test
    void rejectsInvalidIdBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service.getDates(-1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        verifyNoInteractions(repository);
    }
}
