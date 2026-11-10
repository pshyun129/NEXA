package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestPreviewRequest;
import com.ssafy.attendanceservice.domain.attendance.dto.AttendanceRequestPreviewResponse;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequest;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequestItem;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceRequestRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.WorkStatusCodeRepository;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 초과근무 미리보기 구간 검증: 근무일 마감(다음 날 06:00)을 넘는 구간, 구간끼리 겹침,
 * 이미 승인된 초과근무와 겹침 (QA 2026-11-09 AR-44·HM-28). 테스트마다 롤백
 */
@SpringBootTest(properties = {"spring.config.location=classpath:/attendance-ci.yml"})
@Testcontainers
@Transactional
class OvertimePreviewIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final long EMP = 7L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired AttendanceRequestService attendanceRequestService;
    @Autowired AdminAttendanceService adminAttendanceService;
    @Autowired AttendanceRequestRepository attendanceRequestRepository;
    @Autowired WorkStatusCodeRepository workStatusCodeRepository;

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atOffset(KST);
    }

    /** 09:00 출근, 21:00 퇴근 */
    @BeforeEach
    void work() {
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(EMP, DAY, "CLOCK_IN", at(DAY, 9, 0), "test"));
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(EMP, DAY, "CLOCK_OUT", at(DAY, 21, 0), "test"));
    }

    private static AttendanceRequestPreviewRequest.Item item(OffsetDateTime start, OffsetDateTime end) {
        return new AttendanceRequestPreviewRequest.Item(null, null, "WORKING", start, end);
    }

    private AttendanceRequestPreviewResponse preview(AttendanceRequestPreviewRequest.Item... items) {
        return attendanceRequestService.preview(EMP, new AttendanceRequestPreviewRequest(
                AttendanceRequestType.OVERTIME, DAY, null, List.of(items)));
    }

    private void approvedOvertime(OffsetDateTime start, OffsetDateTime end) {
        AttendanceRequest request = overtimeRequest(DAY, start, end);
        request.approve(at(DAY.plusDays(1), 10, 0));
        attendanceRequestRepository.save(request);
    }

    /** 결재 대기 중인 초과근무 신청 */
    private void pendingOvertime(LocalDate date, OffsetDateTime start, OffsetDateTime end) {
        attendanceRequestRepository.save(overtimeRequest(date, start, end));
    }

    private AttendanceRequest overtimeRequest(LocalDate date, OffsetDateTime start, OffsetDateTime end) {
        AttendanceRequest request = AttendanceRequest.builder()
                .empId(EMP).reqType(AttendanceRequestType.OVERTIME).targetDate(date).reason("test").build();
        request.addItem(AttendanceRequestItem.builder()
                .workStatusCode(workStatusCodeRepository.findById("WORKING").orElseThrow())
                .startAt(start).endAt(end).build());
        return request;
    }

    private int weeklyTotal(AttendanceRequestPreviewRequest.Item... items) {
        return preview(items).overtime().weeklyTotalMin();
    }

    @Test
    void 정상_구간은_통과() {
        AttendanceRequestPreviewResponse response = preview(item(at(DAY, 18, 0), at(DAY, 21, 0)));

        assertThat(response.overtime().requestedMin()).isEqualTo(180);
    }

    @Test
    void 근무일_마감_06시를_넘는_구간은_불가() {
        // QA: 00:00 → 다음 날 23:50(47h 50m)이 "확인됨"으로 통과하던 문제
        assertThatThrownBy(() -> preview(item(at(DAY, 0, 0), at(DAY.plusDays(1), 23, 50))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void 다음_날_06시까지는_가능() {
        AttendanceRequestPreviewResponse response = preview(item(at(DAY, 22, 0), at(DAY.plusDays(1), 6, 0)));

        assertThat(response.overtime().requestedMin()).isEqualTo(480);
    }

    @Test
    void 구간끼리_겹치면_불가() {
        assertThatThrownBy(() -> preview(item(at(DAY, 18, 0), at(DAY, 20, 0)), item(at(DAY, 19, 0), at(DAY, 21, 0))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("겹치면");
    }

    @Test
    void 이미_승인된_초과근무와_겹치면_불가() {
        // QA: 19~20시 승인 상태에서 19~21시를 다시 신청할 수 있던 문제
        approvedOvertime(at(DAY, 19, 0), at(DAY, 20, 0));

        assertThatThrownBy(() -> preview(item(at(DAY, 19, 0), at(DAY, 21, 0))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("19:00~20:00");
    }

    @Test
    void 승인된_구간과_맞닿기만_하면_가능() {
        approvedOvertime(at(DAY, 19, 0), at(DAY, 20, 0));

        AttendanceRequestPreviewResponse response = preview(item(at(DAY, 18, 0), at(DAY, 19, 0)), item(at(DAY, 20, 0), at(DAY, 21, 0)));

        assertThat(response.overtime().requestedMin()).isEqualTo(120);
    }

    @Test
    void 이미_일한_미승인_구간은_주간_합계에_두_번_세지_않음() {
        // 18~21시는 퇴근 전에 이미 일해서 근로시간에 들어 있다. 아직 안 한 22~23시만 더해진다
        int alreadyWorked = weeklyTotal(item(at(DAY, 18, 0), at(DAY, 19, 0)));

        assertThat(weeklyTotal(item(at(DAY, 18, 0), at(DAY, 21, 0)))).isEqualTo(alreadyWorked);
        assertThat(weeklyTotal(item(at(DAY, 22, 0), at(DAY, 23, 0)))).isEqualTo(alreadyWorked + 60);
    }

    @Test
    void 같은_주_다른_날_결재_대기_신청도_주간_합계에_넣음() {
        // QA C2: 여러 날 신청을 동시에 올려 주 52시간을 넘길 수 있던 문제
        int before = weeklyTotal(item(at(DAY, 18, 0), at(DAY, 19, 0)));
        LocalDate nextDay = DAY.plusDays(1);
        pendingOvertime(nextDay, at(nextDay, 18, 0), at(nextDay, 20, 0));

        assertThat(weeklyTotal(item(at(DAY, 18, 0), at(DAY, 19, 0)))).isEqualTo(before + 120);
    }
}
