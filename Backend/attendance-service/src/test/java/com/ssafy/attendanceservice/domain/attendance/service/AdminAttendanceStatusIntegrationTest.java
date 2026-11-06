package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.client.EmployeeInfo;
import com.ssafy.attendanceservice.domain.attendance.client.UserClient;
import com.ssafy.attendanceservice.domain.attendance.client.UserClient.DutyInfo;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminDailyStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminDailyStatusResponse.DayStatus;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminPeriodStatusResponse;
import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.ApprovedLeave;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.attendanceservice.global.util.DateTimeUtils;
import com.ssafy.nexa.security.AuthUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 관리자 근태 현황(일자별·기간별)과 월별 집계 권한. user-service는 목으로 대신한다. 테스트마다 롤백
 * 인사팀(1): 7 출근, 8 종일 휴가, 9 퇴사 / 팀장 10
 */
@SpringBootTest(properties = {"spring.config.location=classpath:/attendance-ci.yml"})
@Testcontainers
@Transactional
class AdminAttendanceStatusIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 23);
    private static final long TEAM_LEAD_DUTY = 2L, MEMBER_DUTY = 1L;
    private static final AuthUser TEAM_LEAD = new AuthUser(10L, "USER", 1L, 1L, TEAM_LEAD_DUTY);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired AdminAttendanceStatusService statusService;
    @Autowired AttendanceService attendanceService;
    @Autowired AdminAttendanceService adminAttendanceService;
    @Autowired LeaveService leaveService;

    @MockitoBean UserClient userClient;

    private static EmployeeInfo employee(long empId, long dutyId, String status) {
        return new EmployeeInfo(empId, "사원" + empId, 1L, "인사팀", 1L, dutyId, status);
    }

    @BeforeEach
    void setUp() {
        given(userClient.getEmployees(null)).willReturn(List.of(
                employee(10, TEAM_LEAD_DUTY, "EMPLOYED"),
                employee(7, MEMBER_DUTY, "EMPLOYED"),
                employee(8, MEMBER_DUTY, "EMPLOYED"),
                employee(9, MEMBER_DUTY, "TERMINATED")));
        given(userClient.getOrgChainDeptIds(anyLong())).willReturn(List.of(1L, 10L, 13L));
        given(userClient.getDuty(TEAM_LEAD_DUTY)).willReturn(new DutyInfo(TEAM_LEAD_DUTY, "팀장"));
        given(userClient.getDuty(MEMBER_DUTY)).willReturn(new DutyInfo(MEMBER_DUTY, "팀원"));

        // 7은 수요일 9~18시 근무, 8은 수요일 종일 휴가
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(7L, WEDNESDAY, "CLOCK_IN", at(WEDNESDAY, 9), "test"));
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(7L, WEDNESDAY, "CLOCK_OUT", at(WEDNESDAY, 18), "test"));
        leaveService.applyApprovedLeave(new ApprovedLeave(1L, 8L, "연차", true,
                List.of(new ApprovedLeave.Day(WEDNESDAY, LeavePortion.FULL))));
    }

    private static OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atOffset(KST);
    }

    @Test
    void 일자별_현황은_출근_휴가_미출근을_나눠_센다() {
        AdminDailyStatusResponse daily = statusService.getDaily(TEAM_LEAD, WEDNESDAY, null, null);

        // 퇴사자(9)는 빠지고 사원 ID 순
        assertThat(daily.employees()).extracting(AdminDailyStatusResponse.Employee::empId).containsExactly(7L, 8L, 10L);
        assertThat(daily.employees()).extracting(AdminDailyStatusResponse.Employee::status)
                .containsExactly(DayStatus.WORKED, DayStatus.LEAVE, DayStatus.ABSENT);
        assertThat(daily.summary().targetCount()).isEqualTo(3);
        assertThat(daily.summary().workedCount()).isEqualTo(1);
        assertThat(daily.summary().leaveCount()).isEqualTo(1);
        assertThat(daily.summary().absentCount()).isEqualTo(1);
    }

    @Test
    void 재직_상태를_고르면_그_사원만() {
        AdminDailyStatusResponse daily = statusService.getDaily(TEAM_LEAD, WEDNESDAY, null, "TERMINATED");

        assertThat(daily.employees()).extracting(AdminDailyStatusResponse.Employee::empId).containsExactly(9L);
    }

    @Test
    void 미래_날짜는_400() {
        LocalDate tomorrow = DateTimeUtils.now().toLocalDate().plusDays(1);

        assertThatThrownBy(() -> statusService.getDaily(TEAM_LEAD, tomorrow, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void 기간별_통계는_사원별_합계와_전체_합계() {
        AdminPeriodStatusResponse period = statusService.getPeriod(TEAM_LEAD, MONDAY, FRIDAY, null, null);

        AdminPeriodStatusResponse.Employee worker = period.employees().getFirst();
        assertThat(worker.empId()).isEqualTo(7L);
        assertThat(worker.days().workDays()).isEqualTo(5);
        assertThat(worker.days().attendedDays()).isEqualTo(1);
        assertThat(worker.workTime().totalMin()).isEqualTo(480);
        assertThat(period.employees().get(1).leave().leaveDays()).isEqualTo(1);
        assertThat(period.total().employeeCount()).isEqualTo(3);
        assertThat(period.total().days().attendedDays()).isEqualTo(1);
        assertThat(period.total().leave().leaveMin()).isEqualTo(480);
    }

    @Test
    void 기간이_92일을_넘으면_400() {
        assertThatThrownBy(() -> statusService.getPeriod(TEAM_LEAD, MONDAY, MONDAY.plusDays(92), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void 팀원은_현황을_볼_수_없음() {
        AuthUser member = new AuthUser(7L, "USER", 1L, 1L, MEMBER_DUTY);

        assertThatThrownBy(() -> statusService.getDaily(member, WEDNESDAY, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void 월별_집계는_본인_상급자_관리자만() {
        YearMonth october = YearMonth.of(2026, 10);
        AuthUser member = new AuthUser(7L, "USER", 1L, 1L, MEMBER_DUTY);
        AuthUser admin = new AuthUser(1L, "ADMIN");

        assertThat(attendanceService.getSummary(member, 7L, october).empId()).isEqualTo(7L);
        assertThat(attendanceService.getSummary(TEAM_LEAD, 7L, october).empId()).isEqualTo(7L);
        assertThat(attendanceService.getSummary(admin, 7L, october).empId()).isEqualTo(7L);
        assertThatThrownBy(() -> attendanceService.getSummary(member, 8L, october))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }
}
