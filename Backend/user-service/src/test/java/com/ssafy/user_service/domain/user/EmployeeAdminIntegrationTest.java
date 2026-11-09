package com.ssafy.user_service.domain.user;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.user_service.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 사원 등록/퇴직 API. 각 테스트는 고유한 이메일로 자기 사원을 만든다. */
class EmployeeAdminIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_LOGIN = """
            {"email":"admin@local.dev","pass":"admin1234!"}""";

    @Autowired
    MockMvc mvc;

    private Cookie[] login(String body) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookies();
    }

    private static String loginJson(String email, String pass) {
        return """
                {"email":"%s","pass":"%s"}""".formatted(email, pass);
    }

    private static String employeeJson(String email, long positionId) {
        return """
                {"email":"%s","password":"user12345","name":"Hong","deptId":1,"positionId":%d,
                 "gradeId":1,"dutyId":1,"jobFamilyId":1,"jobKindId":2,"jobRoleId":3,
                 "gender":"MALE","hireType":"RECOMMENDATION","hireDate":"2026-01-02"}""".formatted(email, positionId);
    }

    private long createEmployee(Cookie[] admin, String email) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/employees").with(viaGateway(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(employeeJson(email, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.employeeId").isNumber())
                .andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.employeeId");
        return id.longValue();
    }

    @Test
    void 필수값_누락은_400_필드별_메시지() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        mvc.perform(post("/api/v1/employees").with(viaGateway(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"x","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void 등록_성공_후_같은_이메일은_409() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        createEmployee(admin, "dup@local.dev");
        mvc.perform(post("/api/v1/employees").with(viaGateway(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(employeeJson("dup@local.dev", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMP_002"));
    }

    @Test
    void 일반_사원은_admin_API_403() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        createEmployee(admin, "plain@local.dev");
        Cookie[] user = login(loginJson("plain@local.dev", "user12345"));
        mvc.perform(post("/api/v1/employees").with(viaGateway(user)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
        mvc.perform(patch("/api/v1/employees/1/terminate").with(viaGateway(user)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_009"));
    }

    @Test
    void 퇴직처리_후_재발급과_로그인_차단_중복퇴직은_409() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        long employeeId = createEmployee(admin, "quit@local.dev");
        Cookie[] user = login(loginJson("quit@local.dev", "user12345"));

        mvc.perform(patch("/api/v1/employees/" + employeeId + "/terminate").with(viaGateway(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resignDate":"2026-10-19","resignReason":"이직"}"""))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/reissue").cookie(user))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_006"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("quit@local.dev", "user12345")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_006"));

        mvc.perform(patch("/api/v1/employees/" + employeeId + "/terminate").with(viaGateway(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resignDate":"2026-10-19"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMP_008"));
    }

    // ===== 일괄 조회 (?ids=) =====

    @Test
    void ids로_여러_사원을_한_번에_조회하고_없는_ID는_빠짐() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        long first = createEmployee(admin, "bulk1@local.dev");
        long second = createEmployee(admin, "bulk2@local.dev");

        MvcResult result = mvc.perform(get("/api/v1/employees").with(viaGateway(admin))
                        .param("ids", first + "," + second + ",999999," + first))
                .andExpect(status().isOk())
                .andReturn();
        List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.data.employees[*].empId");
        assertThat(ids.stream().map(Number::longValue).toList()).containsExactlyInAnyOrder(first, second);
        List<String> statuses = JsonPath.read(result.getResponse().getContentAsString(), "$.data.employees[*].status");
        assertThat(statuses).containsOnly("EMPLOYED");
    }

    @Test
    void ids_조회는_퇴직_상태도_그대로_돌려줌() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        long quit = createEmployee(admin, "bulkquit@local.dev");
        mvc.perform(patch("/api/v1/employees/" + quit + "/terminate").with(viaGateway(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"resignDate":"2026-10-28"}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/employees").with(viaGateway(admin)).param("ids", String.valueOf(quit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employees[0].status").value("TERMINATED"));
    }

    @Test
    void ids와_deptId를_함께_쓰거나_100개를_넘으면_400() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        mvc.perform(get("/api/v1/employees").with(viaGateway(admin)).param("ids", "1").param("deptId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));

        String tooMany = IntStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.joining(","));
        mvc.perform(get("/api/v1/employees").with(viaGateway(admin)).param("ids", tooMany))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    // ===== 본인 조회 (/me)·소속 이름 =====

    @Test
    void me는_토큰_주체의_정보와_소속_이름을_돌려준다() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        long employeeId = createEmployee(admin, "me@local.dev");
        Cookie[] user = login(loginJson("me@local.dev", "user12345"));

        // employeeJson: deptId=1(인사팀), positionId=1(사원), gradeId=1(G1), dutyId=1(팀원)
        mvc.perform(get("/api/v1/employees/me").with(viaGateway(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.empId").value(employeeId))
                .andExpect(jsonPath("$.data.loginId").value("me@local.dev"))
                .andExpect(jsonPath("$.data.deptId").value(1))
                .andExpect(jsonPath("$.data.deptName").value("인사팀"))
                .andExpect(jsonPath("$.data.positionName").value("사원"))
                .andExpect(jsonPath("$.data.gradeName").value("G1"))
                .andExpect(jsonPath("$.data.dutyName").value("팀원"));
    }

    @Test
    void me는_퇴직_후_남은_토큰이면_401_AUTH_006() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        long employeeId = createEmployee(admin, "mequit@local.dev");
        Cookie[] user = login(loginJson("mequit@local.dev", "user12345"));
        mvc.perform(patch("/api/v1/employees/" + employeeId + "/terminate").with(viaGateway(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"resignDate":"2026-11-05"}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/employees/me").with(viaGateway(user)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_006"));
    }

    @Test
    void me는_인증이_없으면_401() throws Exception {
        mvc.perform(get("/api/v1/employees/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 단건과_ids_조회에도_소속_이름이_붙는다() throws Exception {
        Cookie[] admin = login(ADMIN_LOGIN);
        long first = createEmployee(admin, "names1@local.dev");
        long second = createEmployee(admin, "names2@local.dev");

        mvc.perform(get("/api/v1/employees/" + first).with(viaGateway(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deptName").value("인사팀"))
                .andExpect(jsonPath("$.data.gradeName").value("G1"));

        MvcResult result = mvc.perform(get("/api/v1/employees").with(viaGateway(admin))
                        .param("ids", first + "," + second))
                .andExpect(status().isOk())
                .andReturn();
        List<String> deptNames = JsonPath.read(result.getResponse().getContentAsString(), "$.data.employees[*].deptName");
        List<String> dutyNames = JsonPath.read(result.getResponse().getContentAsString(), "$.data.employees[*].dutyName");
        assertThat(deptNames).containsExactly("인사팀", "인사팀");
        assertThat(dutyNames).containsExactly("팀원", "팀원");
    }

}
