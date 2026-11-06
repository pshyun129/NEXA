package com.ssafy.attendanceservice.domain.attendance.client;

import com.ssafy.attendanceservice.global.exception.BusinessException;
import com.ssafy.attendanceservice.global.exception.ErrorCode;
import com.ssafy.nexa.security.BearerTokenRelayInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** user-service 사원 목록 호출: 부서 필터, 토큰 전달, 오류 변환 (Spring 컨텍스트 없이 MockRestServiceServer 로) */
class UserClientTest {

    private static final String BASE_URL = "http://user-service";
    private static final String EMPLOYEES_RESPONSE = """
            {"success":true,"error":null,"data":{"employees":[
              {"empId":1,"empNo":"DEV0001","name":"Admin","deptId":1,"positionId":1,"gradeId":1,"dutyId":1,"status":"EMPLOYED"},
              {"empId":2,"empNo":"E0002","name":"Approver","deptId":1,"positionId":1,"gradeId":1,"dutyId":1,"status":"ON_LEAVE"}]}}""";

    private MockRestServiceServer server;
    private UserClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new UserClient(builder, BASE_URL, new BearerTokenRelayInterceptor());

        Jwt jwt = Jwt.withTokenValue("lead-access-token").header("alg", "RS256")
                .subject("1").claim("type", "access").claim("role", "USER").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 부서_사원_목록을_요청한_사원의_토큰으로_조회() {
        server.expect(requestTo(BASE_URL + "/api/v1/employees?deptId=1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer lead-access-token"))
                .andRespond(withSuccess(EMPLOYEES_RESPONSE, MediaType.APPLICATION_JSON));

        List<EmployeeInfo> employees = client.getEmployees(1L);

        assertThat(employees).extracting(EmployeeInfo::empId).containsExactly(1L, 2L);
        assertThat(employees.get(1).status()).isEqualTo("ON_LEAVE");
        server.verify();
    }

    @Test
    void 부서를_안_주면_전체_조회() {
        server.expect(requestTo(BASE_URL + "/api/v1/employees"))
                .andRespond(withSuccess(EMPLOYEES_RESPONSE, MediaType.APPLICATION_JSON));

        assertThat(client.getEmployees(null)).hasSize(2);
        server.verify();
    }

    @Test
    void 직책_이름을_조회() {
        server.expect(requestTo(BASE_URL + "/api/v1/duties/2"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer lead-access-token"))
                .andRespond(withSuccess("""
                        {"success":true,"error":null,"data":{"dutyId":2,"name":"팀장"}}""", MediaType.APPLICATION_JSON));

        assertThat(client.getDuty(2L).name()).isEqualTo("팀장");
        server.verify();
    }

    @Test
    void 소속_부서부터_최상위_부서까지의_부서_ID를_조회() {
        server.expect(requestTo(BASE_URL + "/api/v1/employees/101/org-chain"))
                .andRespond(withSuccess("""
                        {"success":true,"error":null,"data":{"employeeId":101,"deptId":1,"chain":[
                          {"deptId":1,"deptName":"인사팀","deptType":"TEAM","headEmpId":102},
                          {"deptId":10,"deptName":"경영지원본부","deptType":"DIVISION","headEmpId":107},
                          {"deptId":13,"deptName":"DEV COMPANY","deptType":"COMPANY","headEmpId":109}]}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getOrgChainDeptIds(101L)).containsExactly(1L, 10L, 13L);
        server.verify();
    }

    @Test
    void user_service_오류는_502() {
        server.expect(requestTo(BASE_URL + "/api/v1/employees")).andRespond(withServerError());

        assertThatThrownBy(() -> client.getEmployees(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_CALL_FAILED);
    }
}
