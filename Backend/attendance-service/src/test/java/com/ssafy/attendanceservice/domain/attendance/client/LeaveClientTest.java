package com.ssafy.attendanceservice.domain.attendance.client;

import com.ssafy.attendanceservice.domain.attendance.dto.ApprovedLeave;
import com.ssafy.attendanceservice.domain.attendance.entity.enums.LeavePortion;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 휴가 서비스 호출: 요청 형식, 응답 변환, 오류 변환 (Spring 컨텍스트 없이 MockRestServiceServer 로) */
class LeaveClientTest {

    private static final String BASE_URL = "http://leave-service";
    private static final String DATES_URL = BASE_URL + "/api/v1/leaves/internal/requests/42/dates";

    private MockRestServiceServer server;
    private LeaveClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LeaveClient(builder, BASE_URL);
    }

    @Test
    void 휴가_신청의_사원과_날짜를_받아_근태_휴가로_바꿈() {
        server.expect(requestTo(DATES_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("""
                        {"success":true,"error":null,"data":{
                          "requestId":42,"employeeId":7,"leaveTypeName":"연차","isPaid":true,
                          "dates":[{"leaveDate":"2026-11-09","portion":"FULL"},
                                   {"leaveDate":"2026-11-10","portion":"PM_HALF"}]}}""",
                        MediaType.APPLICATION_JSON));

        ApprovedLeave leave = client.getRequestDates(42L).orElseThrow().toApprovedLeave();

        assertThat(leave).isEqualTo(new ApprovedLeave(42L, 7L, "연차", true, List.of(
                new ApprovedLeave.Day(LocalDate.of(2026, 11, 9), LeavePortion.FULL),
                new ApprovedLeave.Day(LocalDate.of(2026, 11, 10), LeavePortion.PM_HALF))));
        server.verify();
    }

    @Test
    void 무급휴가_여부를_받음() {
        server.expect(requestTo(DATES_URL))
                .andRespond(withSuccess("""
                        {"success":true,"error":null,"data":{
                          "requestId":42,"employeeId":7,"leaveTypeName":"무급휴가","isPaid":false,
                          "dates":[{"leaveDate":"2026-11-09","portion":"AM_HALF"}]}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getRequestDates(42L).orElseThrow().isPaid()).isFalse();
    }

    @Test
    void 취소됐거나_없는_신청_404면_빈_값() {
        server.expect(requestTo(DATES_URL)).andRespond(withResourceNotFound());

        assertThat(client.getRequestDates(42L)).isEmpty();
    }

    @Test
    void 휴가_서비스_오류면_예외() {
        server.expect(requestTo(DATES_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.getRequestDates(42L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LEAVE_CALL_FAILED);
    }
}
