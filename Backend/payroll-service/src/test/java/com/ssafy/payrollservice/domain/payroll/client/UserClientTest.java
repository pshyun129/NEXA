package com.ssafy.payrollservice.domain.payroll.client;

import com.ssafy.nexa.security.BearerTokenRelayInterceptor;
import com.ssafy.payrollservice.global.exception.BusinessException;
import com.ssafy.payrollservice.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** user-service 사원 조회: 토큰 전달, 필요한 필드만 받기, 오류 변환 (Spring 컨텍스트 없이 MockRestServiceServer 로) */
class UserClientTest {

    private static final String BASE_URL = "http://user-service";
    private static final String EMPLOYEE_URL = BASE_URL + "/api/v1/employees/101";

    private MockRestServiceServer server;
    private UserClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new UserClient(builder, BASE_URL, new BearerTokenRelayInterceptor());

        Jwt jwt = Jwt.withTokenValue("admin-access-token").header("alg", "RS256")
                .subject("1").claim("type", "access").claim("role", "ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 요청한_관리자의_토큰으로_사원_정보를_조회() {
        server.expect(requestTo(EMPLOYEE_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer admin-access-token"))
                .andRespond(withSuccess("""
                        {"success":true,"error":null,"data":{
                          "empId":101,"empNo":"DEV0101","name":"이서연","deptId":1,"positionId":2,"gradeId":7,"dutyId":1,
                          "hireDate":"2022-03-02","status":"EMPLOYED",
                          "deptName":"인사팀","positionName":"대리","gradeName":"G2","dutyName":"팀원"}}""",
                        MediaType.APPLICATION_JSON));

        EmployeeInfo employee = client.getEmployee(101L);

        assertThat(employee).isEqualTo(
                new EmployeeInfo(101L, "이서연", "인사팀", "G2", 7L, LocalDate.of(2022, 3, 2)));
        server.verify();
    }

    @Test
    void 없는_사원이면_404() {
        server.expect(requestTo(EMPLOYEE_URL)).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.getEmployee(101L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMPLOYEE_NOT_FOUND);
    }

    @Test
    void user_service_오류는_502() {
        server.expect(requestTo(EMPLOYEE_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.getEmployee(101L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_CALL_FAILED);
    }
}
