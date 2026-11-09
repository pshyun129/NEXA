package com.ssafy.attendanceservice.domain.attendance.service;

import com.ssafy.attendanceservice.domain.attendance.dto.AdminWorkStatusLogRequest;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceDaily;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequest;
import com.ssafy.attendanceservice.domain.attendance.entity.AttendanceRequestItem;
import com.ssafy.attendanceservice.domain.attendance.entity.WorkStatusLog;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestStatus;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.RequestItemAction;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.WorkStatusSource;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceDailyRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.AttendanceRequestRepository;
import com.ssafy.attendanceservice.domain.attendance.repository.WorkStatusLogRepository;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결재 결과 반영: 상태 변경, 승인 시 근태 반영(초과근무·근무시간 수정·휴일대체), 중복 결과 무시.
 * 결재 서비스·Kafka 없이 신청서를 직접 저장하고 결과 반영 메서드를 호출한다. 테스트마다 롤백
 */
@SpringBootTest(properties = {"spring.config.location=classpath:/attendance-ci.yml"})
@Testcontainers
@Transactional
class ApprovalResultIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final long EMP = 7L;
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 24);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 27);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired AttendanceRequestService attendanceRequestService;
    @Autowired AdminAttendanceService adminAttendanceService;
    @Autowired AttendanceDailyProcessor processor;
    @Autowired AttendanceRequestRepository attendanceRequestRepository;
    @Autowired AttendanceDailyRepository attendanceDailyRepository;
    @Autowired WorkStatusLogRepository workStatusLogRepository;

    private static OffsetDateTime at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atOffset(KST);
    }

    /** 관리자 로그 등록으로 그날 출퇴근 기록을 만든다 (근태 재계산 포함) */
    private void work(LocalDate date, int fromHour, int toHour) {
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(EMP, date, "CLOCK_IN", at(date, fromHour, 0), "test"));
        adminAttendanceService.registerLog(1L, new AdminWorkStatusLogRequest(EMP, date, "CLOCK_OUT", at(date, toHour, 0), "test"));
    }

    private AttendanceDaily daily(LocalDate date) {
        return attendanceDailyRepository.findByEmpIdAndWorkDate(EMP, date).orElseThrow();
    }

    private AttendanceRequest overtimeRequest(LocalDate date, int fromHour, int toHour) {
        AttendanceRequest request = AttendanceRequest.builder()
                .empId(EMP).reqType(AttendanceRequestType.OVERTIME).targetDate(date).reason("ot").build();
        request.addItem(AttendanceRequestItem.builder()
                .workStatusCode(processor.getStatusCode("WORKING"))
                .startAt(at(date, fromHour, 0)).endAt(at(date, toHour, 0)).build());
        return attendanceRequestRepository.save(request);
    }

    @Test
    void 초과근무_승인_시_신청_구간만_승인_초과근로로() {
        work(WEDNESDAY, 9, 21);                                   // 연장 18~21시 180분, 전부 미승인
        AttendanceRequest request = overtimeRequest(WEDNESDAY, 18, 20);

        attendanceRequestService.applyApprovalResult(request.getAttReqId(), AttendanceRequestStatus.APPROVED);

        assertThat(request.getStatus()).isEqualTo(AttendanceRequestStatus.APPROVED);
        assertThat(request.getCompletedAt()).isNotNull();
        assertThat(daily(WEDNESDAY).getApprovedOtMin()).isEqualTo(120);
        assertThat(daily(WEDNESDAY).getUnapprovedOtMin()).isEqualTo(60);
    }

    @Test
    void 반려는_상태만_바꾸고_근태는_그대로() {
        work(WEDNESDAY, 9, 21);
        AttendanceRequest request = overtimeRequest(WEDNESDAY, 18, 20);

        attendanceRequestService.applyApprovalResult(request.getAttReqId(), AttendanceRequestStatus.REJECTED);

        assertThat(request.getStatus()).isEqualTo(AttendanceRequestStatus.REJECTED);
        assertThat(daily(WEDNESDAY).getApprovedOtMin()).isZero();
        assertThat(daily(WEDNESDAY).getUnapprovedOtMin()).isEqualTo(180);
    }

    @Test
    void 이미_처리된_신청의_결과는_무시() {
        work(WEDNESDAY, 9, 21);
        AttendanceRequest request = overtimeRequest(WEDNESDAY, 18, 20);
        attendanceRequestService.applyApprovalResult(request.getAttReqId(), AttendanceRequestStatus.APPROVED);

        attendanceRequestService.applyApprovalResult(request.getAttReqId(), AttendanceRequestStatus.APPROVED);
        attendanceRequestService.applyApprovalResult(request.getAttReqId(), AttendanceRequestStatus.REJECTED);

        assertThat(request.getStatus()).isEqualTo(AttendanceRequestStatus.APPROVED);
        assertThat(daily(WEDNESDAY).getApprovedOtMin()).isEqualTo(120);
    }

    @Test
    void 근무시간_수정_승인_시_로그_적용() {
        work(WEDNESDAY, 9, 21);
        WorkStatusLog clockOut = workStatusLogRepository
                .findByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtAscLogIdAsc(EMP, WEDNESDAY).getLast();
        AttendanceRequest request = AttendanceRequest.builder()
                .empId(EMP).reqType(AttendanceRequestType.TIME_FIX).targetDate(WEDNESDAY).reason("fix").build();
        request.addItem(AttendanceRequestItem.builder()
                .action(RequestItemAction.MODIFY).targetLogId(clockOut.getLogId())
                .workStatusCode(processor.getStatusCode("CLOCK_OUT"))
                .startAt(at(WEDNESDAY, 18, 0)).build());
        attendanceRequestRepository.save(request);

        attendanceRequestService.applyApprovalResult(request.getAttReqId(), AttendanceRequestStatus.APPROVED);

        assertThat(clockOut.getIsDeleted()).isTrue();
        WorkStatusLog newClockOut = workStatusLogRepository
                .findByEmpIdAndWorkDateAndIsDeletedFalseOrderByStartAtAscLogIdAsc(EMP, WEDNESDAY).getLast();
        assertThat(newClockOut.getSource()).isEqualTo(WorkStatusSource.APPROVAL);
        assertThat(daily(WEDNESDAY).getClockOut()).isEqualTo(at(WEDNESDAY, 18, 0));
        assertThat(daily(WEDNESDAY).getOtExtendedMin()).isZero();
    }

    @Test
    void 휴일대체_승인_시_근무한_휴일은_근무일로_대체일은_휴일로() {
        work(SATURDAY, 10, 15);                                   // 휴일근로 270분
        assertThat(daily(SATURDAY).getIsHoliday()).isTrue();
        AttendanceRequest request = attendanceRequestRepository.save(AttendanceRequest.builder()
                .empId(EMP).reqType(AttendanceRequestType.HOLIDAY_SUBST)
                .targetDate(SATURDAY).substDate(TUESDAY).reason("subst").build());

        attendanceRequestService.applyApprovalResult(request.getAttReqId(), AttendanceRequestStatus.APPROVED);

        assertThat(daily(SATURDAY).getIsHoliday()).isFalse();
        assertThat(daily(SATURDAY).getOtHolidayMin()).isZero();
        assertThat(daily(SATURDAY).getRegularMin()).isEqualTo(270);
        assertThat(processor.isHoliday(EMP, TUESDAY, processor.resolveWorkGroup(EMP, TUESDAY))).isTrue();
    }

    @Test
    void 신청서가_없으면_404로_재시도_대상() {
        assertThatThrownBy(() -> attendanceRequestService.applyApprovalResult(999_999L, AttendanceRequestStatus.APPROVED))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ATTENDANCE_REQUEST_NOT_FOUND);
    }
}
