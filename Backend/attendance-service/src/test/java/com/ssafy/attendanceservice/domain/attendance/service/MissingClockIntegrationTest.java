package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogRequest;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AnomalyType;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceAnomalyRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceDailyRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.WorkStatusLogRepository;
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
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 출퇴근 누락: 다음 출근을 기다리지 않고, 퇴근 없이 근무일 마감이 지난 날을 누락으로 본다 (QA 2026-11-09 AT-35·C11).
 * 테스트마다 롤백
 */
@SpringBootTest(properties = {"spring.config.location=classpath:/attendance-ci.yml"})
@Testcontainers
@Transactional
class MissingClockIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final long EMP = 7L;
    private static final LocalDate PAST_DAY = LocalDate.of(2026, 10, 21);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired AdminAttendanceService adminAttendanceService;
    @Autowired AttendanceService attendanceService;
    @Autowired AttendanceDailyRepository attendanceDailyRepository;
    @Autowired AttendanceAnomalyRepository attendanceAnomalyRepository;
    @Autowired WorkStatusLogRepository workStatusLogRepository;

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atOffset(KST);
    }

    @Test
    void 지난_날에_출근만_등록하면_출퇴근_누락으로_저장() {
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(EMP, PAST_DAY, "CLOCK_IN", at(PAST_DAY, 9, 0), "test"));

        var daily = attendanceDailyRepository.findByEmpIdAndWorkDate(EMP, PAST_DAY).orElseThrow();
        assertThat(attendanceAnomalyRepository.findByAttendanceDailyOrderByAnomalyIdAsc(daily))
                .extracting(a -> a.getAnomalyType())
                .containsOnlyOnce(AnomalyType.MISSING_CLOCK);
        assertThat(attendanceService.getDaily(EMP, PAST_DAY).anomalies()).containsOnlyOnce(AnomalyType.MISSING_CLOCK);
    }

    @Test
    void 출근_기록까지_지우면_출퇴근_누락이_아님() {
        var registered = adminAttendanceService.registerLog(1L,
                new AdminWorkStatusLogRequest(EMP, PAST_DAY, "CLOCK_IN", at(PAST_DAY, 9, 0), "test"));
        Long logId = workStatusLogRepository
                .findByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtAscLogIdAsc(EMP, PAST_DAY).get(0).getLogId();

        adminAttendanceService.deleteLog(logId);

        assertThat(registered.anomalies()).contains(AnomalyType.MISSING_CLOCK);
        assertThat(attendanceService.getDaily(EMP, PAST_DAY).anomalies()).doesNotContain(AnomalyType.MISSING_CLOCK);
    }

    @Test
    void 퇴근을_등록하면_출퇴근_누락이_풀림() {
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(EMP, PAST_DAY, "CLOCK_IN", at(PAST_DAY, 9, 0), "test"));
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(EMP, PAST_DAY, "CLOCK_OUT", at(PAST_DAY, 18, 0), "test"));

        assertThat(attendanceService.getDaily(EMP, PAST_DAY).anomalies()).doesNotContain(AnomalyType.MISSING_CLOCK);
    }
}
