package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.ApprovedLeave;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestPreviewRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceSummaryResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.HolidayResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequest;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceAnomalyRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceDailyRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceRequestRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.EmployeeLeaveRepository;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 휴가 반영: 반차 날 지각·조퇴·미달 판정, 휴가 저장·취소 후 다시 판정, 휴가 날 출근·신청 막기,
 * 월 집계의 휴가 시간, 휴가 못 쓰는 날 조회. 휴가 서비스 없이 휴가를 직접 저장한다. 테스트마다 롤백
 */
@SpringBootTest(properties = {"spring.config.location=classpath:/attendance-ci.yml"})
@Testcontainers
@Transactional
class LeaveIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final long EMP = 7L;
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 27);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired LeaveService leaveService;
    @Autowired AttendanceService attendanceService;
    @Autowired AdminAttendanceService adminAttendanceService;
    @Autowired AttendanceRequestService attendanceRequestService;
    @Autowired AttendanceDailyRepository attendanceDailyRepository;
    @Autowired AttendanceAnomalyRepository attendanceAnomalyRepository;
    @Autowired AttendanceRequestRepository attendanceRequestRepository;
    @Autowired EmployeeLeaveRepository employeeLeaveRepository;

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atOffset(KST);
    }

    /** 관리자 로그 등록으로 그날 출퇴근 기록을 만든다 (근태 재계산 포함) */
    private void work(LocalDate date, int fromHour, int toHour) {
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(EMP, date, "CLOCK_IN", at(date, fromHour, 0), "test"));
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(EMP, date, "CLOCK_OUT", at(date, toHour, 0), "test"));
    }

    private void leave(long leaveReqId, LocalDate date, LeavePortion portion, boolean isPaid) {
        leaveService.applyApprovedLeave(new ApprovedLeave(leaveReqId, EMP, isPaid ? "연차" : "무급휴가", isPaid,
                List.of(new ApprovedLeave.Day(date, portion))));
    }

    private List<AnomalyType> anomalies(LocalDate date) {
        return attendanceAnomalyRepository.findByAttendanceDailyOrderByAnomalyIdAsc(
                        attendanceDailyRepository.findByEmpIdAndWorkDate(EMP, date).orElseThrow()).stream()
                .map(anomaly -> anomaly.getAnomalyType())
                .toList();
    }

    @Test
    void 반차가_없으면_14시_출근_18시_퇴근은_지각과_미달() {
        work(WEDNESDAY, 14, 18);

        assertThat(anomalies(WEDNESDAY)).contains(AnomalyType.LATE, AnomalyType.UNDER_STD_HOURS);
    }

    @Test
    void 오전_반차_날은_14시_출근해도_지각_미달_아님() {
        leave(1L, WEDNESDAY, LeavePortion.AM_HALF, true);

        work(WEDNESDAY, 14, 18);

        assertThat(anomalies(WEDNESDAY)).isEmpty();
    }

    @Test
    void 오후_반차_날은_13시_퇴근해도_조퇴_미달_아님() {
        leave(1L, WEDNESDAY, LeavePortion.PM_HALF, true);

        work(WEDNESDAY, 9, 13);

        assertThat(anomalies(WEDNESDAY)).isEmpty();
    }

    @Test
    void 오전_반차라도_14시_넘어_출근하면_지각() {
        leave(1L, WEDNESDAY, LeavePortion.AM_HALF, true);

        work(WEDNESDAY, 15, 18);

        assertThat(anomalies(WEDNESDAY)).containsExactly(AnomalyType.LATE);
    }

    @Test
    void 출퇴근_뒤에_휴가가_승인되면_다시_판정() {
        work(WEDNESDAY, 14, 18);

        leave(1L, WEDNESDAY, LeavePortion.AM_HALF, true);

        assertThat(anomalies(WEDNESDAY)).isEmpty();
    }

    @Test
    void 휴가를_취소하면_다시_지각과_미달() {
        leave(1L, WEDNESDAY, LeavePortion.AM_HALF, true);
        work(WEDNESDAY, 14, 18);

        leaveService.cancelLeave(1L);

        assertThat(employeeLeaveRepository.findByLeaveReqId(1L)).isEmpty();
        assertThat(anomalies(WEDNESDAY)).contains(AnomalyType.LATE, AnomalyType.UNDER_STD_HOURS);
    }

    @Test
    void 같은_휴가가_두_번_와도_한_번만_저장() {
        leave(1L, WEDNESDAY, LeavePortion.FULL, true);
        leave(1L, WEDNESDAY, LeavePortion.FULL, true);

        assertThat(employeeLeaveRepository.findByLeaveReqId(1L)).hasSize(1);
    }

    @Test
    void 반차_시각은_구분별_고정값으로_저장() {
        leave(1L, WEDNESDAY, LeavePortion.PM_HALF, true);

        var saved = employeeLeaveRepository.findByLeaveReqId(1L).getFirst();
        assertThat(saved.getStartAt()).isEqualTo(at(WEDNESDAY, 14, 0));
        assertThat(saved.getEndAt()).isEqualTo(at(WEDNESDAY, 18, 0));
    }

    @Test
    void 종일_휴가_날은_출근_불가() {
        leave(1L, DateTimeUtils.now().toLocalDate(), LeavePortion.FULL, true);

        assertThatThrownBy(() -> attendanceService.clockIn(EMP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LEAVE_DAY_CLOCK_IN);
    }

    @Test
    void 오전_오후_반차를_따로_쓴_날도_출근_불가() {
        LocalDate today = DateTimeUtils.now().toLocalDate();
        leave(1L, today, LeavePortion.AM_HALF, true);
        leave(2L, today, LeavePortion.PM_HALF, true);

        assertThatThrownBy(() -> attendanceService.clockIn(EMP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LEAVE_DAY_CLOCK_IN);
    }

    @Test
    void 종일_휴가_날은_초과근무_신청_불가() {
        leave(1L, WEDNESDAY, LeavePortion.FULL, true);
        AttendanceRequestPreviewRequest request = new AttendanceRequestPreviewRequest(
                AttendanceRequestType.OVERTIME, WEDNESDAY, null,
                List.of(new AttendanceRequestPreviewRequest.Item(
                        null, null, "WORKING", at(WEDNESDAY, 18, 0), at(WEDNESDAY, 20, 0))));

        assertThatThrownBy(() -> attendanceRequestService.preview(EMP, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LEAVE_DAY_REQUEST);
    }

    @Test
    void 대체일에_휴가가_있으면_휴일대체_신청_불가() {
        LocalDate saturday = LocalDate.of(2026, 10, 24);
        leave(1L, TUESDAY, LeavePortion.AM_HALF, true);
        AttendanceRequestPreviewRequest request = new AttendanceRequestPreviewRequest(
                AttendanceRequestType.HOLIDAY_SUBST, saturday, TUESDAY, null);

        assertThatThrownBy(() -> attendanceRequestService.preview(EMP, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LEAVE_DAY_REQUEST);
    }

    @Test
    void 월_집계에_휴가_시간과_무급_시간() {
        leave(1L, WEDNESDAY, LeavePortion.FULL, true);
        leave(2L, TUESDAY, LeavePortion.PM_HALF, false);

        AttendanceSummaryResponse summary = attendanceService.getSummary(EMP, YearMonth.of(2026, 10));

        assertThat(summary.leave()).isEqualTo(new AttendanceSummaryResponse.Leave(1, 720, 240));
    }

    @Test
    void 휴가_못_쓰는_날에_대체휴일은_넣고_대체근무한_공휴일은_뺀다() {
        AttendanceRequest subst = AttendanceRequest.builder()
                .empId(EMP).reqType(AttendanceRequestType.HOLIDAY_SUBST)
                .targetDate(LocalDate.of(2026, 10, 9))      // 한글날에 근무
                .substDate(LocalDate.of(2026, 10, 14))      // 대신 수요일에 쉼
                .reason("subst").build();
        subst.approve(DateTimeUtils.now());
        attendanceRequestRepository.save(subst);

        List<HolidayResponse> holidays = attendanceService.getHolidays(
                EMP, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(holidays).extracting(HolidayResponse::holidayDate)
                .contains(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 14))
                .doesNotContain(LocalDate.of(2026, 10, 9));
        assertThat(holidays).filteredOn(h -> h.holidayDate().equals(LocalDate.of(2026, 10, 14)))
                .extracting(HolidayResponse::type)
                .containsExactly("SUBSTITUTE");
    }
}
