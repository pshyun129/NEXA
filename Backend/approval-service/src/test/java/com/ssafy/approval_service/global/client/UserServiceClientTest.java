package com.ssafy.approval_service.global.client;

import com.ssafy.approval_service.global.client.UserServiceClient.EmployeeStatus;
import com.ssafy.nexa.security.BearerTokenRelayInterceptor;
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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** user-service 호출: 요청 형식, 토큰 전달, 응답 변환 (Spring 컨텍스트 없이 MockRestServiceServer 로) */
class UserServiceClientTest {

    private static final String BASE_URL = "http://user-service";

    private MockRestServiceServer server;
    private UserServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = UserServiceClientConfig.create(builder, new BearerTokenRelayInterceptor());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String tokenValue) {
        Jwt jwt = Jwt.withTokenValue(tokenValue).header("alg", "RS256").subject("30").claim("type", "access").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void 요청자의_access_토큰을_Bearer로_넘기고_ids를_쿼리로_보냄() {
        authenticate("access-token-value");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/api/v1/employees")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("ids", "20", "30"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token-value"))
                .andRespond(withSuccess("""
                        {"success":true,"error":null,"data":{"employees":[
                          {"empId":20,"empNo":"E20","name":"홍길동","deptId":1,"status":"EMPLOYED"},
                          {"empId":30,"empNo":"E30","name":"김철수","deptId":2,"status":"TERMINATED"}]}}""",
                        MediaType.APPLICATION_JSON));

        List<EmployeeStatus> employees = client.getEmployees(List.of(20L, 30L)).data().employees();

        assertThat(employees).containsExactly(new EmployeeStatus(20L, "EMPLOYED"), new EmployeeStatus(30L, "TERMINATED"));
        server.verify();
    }

    @Test
    void 인증_정보가_없으면_Authorization_헤더를_붙이지_않음() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/api/v1/employees")))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("""
                        {"success":true,"data":{"employees":[]}}""", MediaType.APPLICATION_JSON));

        assertThat(client.getEmployees(List.of(1L)).data().employees()).isEmpty();
        server.verify();
    }

    @Test
    void user_service_오류_응답은_RestClientException() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL + "/api/v1/employees")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getEmployees(List.of(1L))).isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void 부서_체인은_사원_ID를_경로로_보내고_공석은_null로_받음() {
        authenticate("access-token-value");
        server.expect(requestTo(BASE_URL + "/api/v1/employees/103/org-chain"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token-value"))
                .andRespond(withSuccess("""
                        {"success":true,"error":null,"data":{"employeeId":103,"deptId":2,"chain":[
                          {"deptId":2,"deptName":"개발팀","deptType":"TEAM","headEmpId":105},
                          {"deptId":12,"deptName":"영업본부","deptType":"DIVISION","headEmpId":null},
                          {"deptId":13,"deptName":"DEV COMPANY","deptType":"COMPANY","headEmpId":109,"extra":"무시"}]}}""",
                        MediaType.APPLICATION_JSON));

        UserServiceClient.OrgChain chain = client.getOrgChain(103L).data();

        assertThat(chain.deptId()).isEqualTo(2L);
        assertThat(chain.chain()).containsExactly(
                new UserServiceClient.OrgUnit(2L, "개발팀", "TEAM", 105L),
                new UserServiceClient.OrgUnit(12L, "영업본부", "DIVISION", null),
                new UserServiceClient.OrgUnit(13L, "DEV COMPANY", "COMPANY", 109L));
        server.verify();
    }
}
