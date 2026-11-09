package com.ssafy.approval_service.domain.approval;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import com.ssafy.approval_service.support.FakeUserServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.BASE;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.line;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.path;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결재선에 사원을 추가할 때의 존재·재직 확인. 역할과 관계없이 재직(EMPLOYED) 사원만 넣을 수 있고,
 * user-service 에 확인할 수 없으면 503 으로 막는다 (상신 때 결재선은 규정과 조직도로 만들므로 여기서 다루지 않음). user-service 는 FakeUserServiceClient (사원 ID 300_000번대).
 */
class EmployeeVerificationIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicLong EMP_SEQ = new AtomicLong(300_000);

    @Autowired
    FakeUserServiceClient userService;

    @AfterEach
    void tearDown() {
        userService.reset();
    }

    private static long newEmployee() {
        return EMP_SEQ.incrementAndGet();
    }

    private ResultActions addApprover(long documentId, long drafter, String line) throws Exception {
        return mvc.perform(post(path(documentId, "/approvers")).with(user(drafter))
                .contentType(MediaType.APPLICATION_JSON).content(line));
    }

    private ResultActions addParticipant(long documentId, String sub, long drafter, long empId) throws Exception {
        return mvc.perform(post(path(documentId, sub)).with(user(drafter))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"empId":%d}""".formatted(empId)));
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private List<Number> lineEmpIds(long documentId, long drafter) throws Exception {
        String body = mvc.perform(get(path(documentId, "")).with(user(drafter)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.approvalLines[*].empId");
    }

    private List<Number> listedDocuments(long drafter) throws Exception {
        String body = mvc.perform(get(BASE).with(user(drafter)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.documents[*].documentId");
    }

    // ===== 결재선 변경 =====

    @Test
    void 결재자_참조자_열람자_추가도_재직_중인_사원만() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long documentId = createDocument(drafter, line(approver, 1, "APPROVER"));
        long ghost = newEmployee();
        long terminated = newEmployee();
        long onLeave = newEmployee();
        userService.markMissing(ghost);
        userService.setStatus(terminated, "TERMINATED");
        userService.setStatus(onLeave, "ON_LEAVE");

        expectError(addApprover(documentId, drafter, line(ghost, 2, "APPROVER")), 400, "APPROVAL_015");
        expectError(addApprover(documentId, drafter, line(onLeave, 2, "AGREEMENT")), 400, "APPROVAL_016");
        expectError(addParticipant(documentId, "/referrers", drafter, terminated), 400, "APPROVAL_016");
        expectError(addParticipant(documentId, "/viewers", drafter, onLeave), 400, "APPROVAL_016");
        expectError(addApprover(documentId, drafter, line(drafter, 2, "APPROVER")), 400, "APPROVAL_002");

        assertThat(lineEmpIds(documentId, drafter)).extracting(Number::longValue).containsExactly(approver);
        addParticipant(documentId, "/viewers", drafter, newEmployee()).andExpect(status().isCreated());
    }

    // ===== user-service 장애 =====

    @Test
    void user_service에_확인할_수_없으면_503_APPROVAL_017_저장하지_않음() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long documentId = createDocument(drafter, line(approver, 1, "APPROVER"));
        userService.setDown(true);

        expectError(submit(drafter), 503, "APPROVAL_017");   // 조직도 조회 실패
        expectError(addApprover(documentId, drafter, line(newEmployee(), 2, "APPROVER")), 503, "APPROVAL_017");
        expectError(addParticipant(documentId, "/viewers", drafter, newEmployee()), 503, "APPROVAL_017");

        assertThat(listedDocuments(drafter)).extracting(Number::longValue).containsExactly(documentId);
        assertThat(lineEmpIds(documentId, drafter)).extracting(Number::longValue).containsExactly(approver);

        // user-service 를 거치지 않는 결재 처리는 그대로 동작
        mvc.perform(post(path(documentId, "/approve")).with(user(approver))).andExpect(status().isOk());
    }
}
