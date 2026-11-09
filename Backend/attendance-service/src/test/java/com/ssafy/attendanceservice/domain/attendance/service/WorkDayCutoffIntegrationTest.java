package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceAnomalyRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceDailyRepository;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 퇴근을 찍지 않고 근무일 마감(다음날 06:00)이 지난 근무: 오늘 상태는 출근 전, 퇴근·상태 변경은 거절,
 * 오늘 출근은 가능하고 이전 근무는 출퇴근 누락. 지난 날짜에 출근만 등록해 둔다. 테스트마다 롤백
 */
@SpringBootTest(properties = {"spring.config.location=classpath:/attendance-ci.yml"})
@Testcontainers
@Transactional
class WorkDayCutoffIntegrationTest {

    private static final long EMP = 7L;
    private static final LocalDate PAST_DAY = LocalDate.of(2026, 10, 21);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired AttendanceService attendanceService;
    @Autowired AdminAttendanceService adminAttendanceService;
    @Autowired AttendanceDailyRepository attendanceDailyRepository;
    @Autowired AttendanceAnomalyRepository attendanceAnomalyRepository;

    /** 지난 날 출근만 하고 퇴근은 찍지 않음 */
    @BeforeEach
    void clockInWithoutClockOut() {
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(
                EMP, PAST_DAY, "CLOCK_IN", PAST_DAY.atTime(8, 46).atOffset(ZoneOffset.ofHours(9)), "test"));
    }

    @Test
    void 오늘_상태는_출근_전() {
        AttendanceStatusResponse status = attendanceService.getToday(EMP);

        assertThat(status.workDate()).isEqualTo(DateTimeUtils.now().toLocalDate());
        assertThat(status.clockIn()).isNull();
        assertThat(status.statusCode()).isNull();
    }

    @Test
    void 마감이_지난_근무에_퇴근을_붙이지_않음() {
        assertThatThrownBy(() -> attendanceService.clockOut(EMP))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORK_DAY_CLOSED);

        assertThat(attendanceDailyRepository.findByEmpIdAndWorkDate(EMP, PAST_DAY).orElseThrow().getClockOut()).isNull();
    }

    @Test
    void 마감이_지난_근무는_상태_변경_불가() {
        assertThatThrownBy(() -> attendanceService.changeStatus(EMP, "WORKING"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORK_DAY_CLOSED);
    }

    @Test
    void 오늘_출근은_가능하고_이전_근무는_출퇴근_누락() {
        AttendanceStatusResponse status = attendanceService.clockIn(EMP);

        assertThat(status.workDate()).isEqualTo(DateTimeUtils.now().toLocalDate());
        assertThat(attendanceAnomalyRepository.findByAttendanceDailyOrderByAnomalyIdAsc(
                        attendanceDailyRepository.findByEmpIdAndWorkDate(EMP, PAST_DAY).orElseThrow()).stream()
                .map(anomaly -> anomaly.getAnomalyType()))
                .contains(AnomalyType.MISSING_CLOCK);
    }
}
