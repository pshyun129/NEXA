package com.ssafy.attendanceservice.domain.attendance.client;

import com.ssafy.attendanceservice.domain.attendance.entity.enums.AttendanceRequestType;
import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.nexa.security.BearerTokenRelayInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 결재 서비스 호출: 토큰 전달, 요청 형식, 오류 변환 (Spring 컨텍스트 없이 MockRestServiceServer 로) */
class ApprovalClientTest {

    private static final String BASE_URL = "http://approval-service";
    private static final String DOCUMENTS_URL = BASE_URL + "/api/v1/documents";
    private static final String CANCEL_URL = DOCUMENTS_URL + "/128/cancel";

    private MockRestServiceServer server;
    private ApprovalClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ApprovalClient(builder, BASE_URL, new BearerTokenRelayInterceptor());

        Jwt jwt = Jwt.withTokenValue("employee-access-token").header("alg", "RS256")
                .subject("7").claim("type", "access").claim("role", "USER").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static ApprovalDocumentCreateRequest overtimeRequest() {
        return ApprovalDocumentCreateRequest.ofAttendance("초과근무 신청 (2026-11-05)",
                AttendanceRequestType.OVERTIME, 42L, new ApprovalDocumentCreateRequest.Attributes(120, 600));
    }

    @Test
    void 신청한_사원의_토큰으로_근태_출처_문서를_만듦() {
        server.expect(requestTo(DOCUMENTS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer employee-access-token"))
                .andExpect(content().json("""
                        {"title":"초과근무 신청 (2026-11-05)","formType":"OVERTIME",
                         "sourceType":"ATTENDANCE","sourceId":42,
                         "attributes":{"overtimeMinutes":120,"monthlyOvertimeMinutes":600}}"""))
                .andExpect(jsonPath("$.approvalLines").doesNotExist())
                .andExpect(jsonPath("$.requestKey").value(ApprovalDocumentCreateRequest.requestKeyOf(42L).toString()))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"documentId":128,"docNo":"OVERTIME-2026-0001"}}""",
                        MediaType.APPLICATION_JSON));

        ApprovalDocument document = client.createDocument(overtimeRequest());

        assertThat(document.documentId()).isEqualTo(128L);
        assertThat(document.docNo()).isEqualTo("OVERTIME-2026-0001");
        server.verify();
    }

    @Test
    void 결재_서비스_400은_메시지를_그대로_400으로() {
        server.expect(requestTo(DOCUMENTS_URL))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body("""
                        {"success":false,"error":{"code":"APPROVAL_002","message":"결재선에 기안자 본인을 넣을 수 없습니다."},"data":null}"""));

        assertThatThrownBy(() -> client.createDocument(overtimeRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("결재선에 기안자 본인을 넣을 수 없습니다.")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.APPROVAL_REQUEST_REJECTED);
    }

    @Test
    void 결재_서비스_오류는_502() {
        server.expect(requestTo(DOCUMENTS_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.createDocument(overtimeRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.APPROVAL_CALL_FAILED);
    }

    @Test
    void 요청_키는_신청_ID마다_항상_같은_값() {
        assertThat(ApprovalDocumentCreateRequest.requestKeyOf(42L))
                .isEqualTo(ApprovalDocumentCreateRequest.requestKeyOf(42L))
                .isNotEqualTo(ApprovalDocumentCreateRequest.requestKeyOf(43L));
    }

    @Test
    void 초과근무가_아니면_attributes를_보내지_않음() {
        server.expect(requestTo(DOCUMENTS_URL))
                .andExpect(jsonPath("$.formType").value("TIME_FIX"))
                .andExpect(jsonPath("$.attributes").doesNotExist())
                .andRespond(withSuccess("""
                        {"success":true,"data":{"documentId":129,"docNo":"TIME_FIX-2026-0001"}}""",
                        MediaType.APPLICATION_JSON));

        client.createDocument(ApprovalDocumentCreateRequest.ofAttendance("근무시간 수정 신청 (2026-11-05)",
                AttendanceRequestType.TIME_FIX, 43L, null));

        server.verify();
    }

    @Test
    void 신청한_사원의_토큰으로_결재_문서를_회수() {
        server.expect(requestTo(CANCEL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer employee-access-token"))
                .andRespond(withSuccess("""
                        {"success":true,"data":null}""", MediaType.APPLICATION_JSON));

        client.cancelDocument(128L);

        server.verify();
    }

    @Test
    void 결재가_진행된_문서는_409와_결재_서비스_메시지() {
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"success":false,"error":{"code":"APPROVAL_008","message":"결재가 진행된 문서는 회수할 수 없습니다."},"data":null}"""));

        assertThatThrownBy(() -> client.cancelDocument(128L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("결재가 진행된 문서는 회수할 수 없습니다.")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.APPROVAL_CANCEL_REJECTED);
    }

    @Test
    void 회수_중_결재_서비스_오류는_502() {
        server.expect(requestTo(CANCEL_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.cancelDocument(128L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.APPROVAL_CALL_FAILED);
    }
}
