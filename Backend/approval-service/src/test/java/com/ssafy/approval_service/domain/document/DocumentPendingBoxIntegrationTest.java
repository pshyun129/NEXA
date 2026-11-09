package com.ssafy.approval_service.domain.document;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.BASE;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.admin;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.line;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.path;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결재 대기함(box=PENDING), 건수 API, 목록 항목의 진행 상황(현재 결재자·단계·내 차례).
 * 차례 규칙은 결재 처리(approve)의 검증과 같아야 하므로, 목록에 나온 대로 승인이 되는지까지 확인한다.
 */
class DocumentPendingBoxIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicLong EMP_SEQ = new AtomicLong(400_000);

    private static long newEmployee() {
        return EMP_SEQ.incrementAndGet();
    }

    private ResultActions pendingBox(long who) throws Exception {
        return mvc.perform(get(BASE).param("box", "PENDING").with(user(who)));
    }

    private List<Integer> pendingIds(long who) throws Exception {
        String body = pendingBox(who).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.documents[*].documentId");
    }

    private long pendingCount(long who) throws Exception {
        String body = mvc.perform(get(BASE + "/count").param("box", "PENDING").with(user(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.count")).longValue();
    }

    private void approve(long documentId, long who) throws Exception {
        mvc.perform(post(path(documentId, "/approve")).with(user(who))).andExpect(status().isOk());
    }

    @Test
    void 차례가_된_결재자만_대기함에_보이고_승인하면_다음_결재자로_넘어간다() throws Exception {
        long drafter = newEmployee();
        long first = newEmployee();
        long second = newEmployee();
        long referrer = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(second, 2, "AGREEMENT"),
                line(referrer, 3, "REFERENCE"));

        assertThat(pendingIds(first)).containsExactly((int) documentId);
        assertThat(pendingIds(second)).isEmpty();
        assertThat(pendingIds(referrer)).isEmpty();
        assertThat(pendingIds(drafter)).isEmpty();
        assertThat(pendingCount(first)).isEqualTo(1);
        assertThat(pendingCount(second)).isZero();

        approve(documentId, first);

        assertThat(pendingIds(first)).isEmpty();
        assertThat(pendingIds(second)).containsExactly((int) documentId);
        assertThat(pendingCount(second)).isEqualTo(1);

        approve(documentId, second);

        assertThat(pendingIds(second)).isEmpty();
        assertThat(pendingCount(second)).isZero();
    }

    @Test
    void 목록_항목에_출처와_진행_상황이_나온다() throws Exception {
        long drafter = newEmployee();
        long first = newEmployee();
        long second = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(second, 2, "APPROVER"));

        pendingBox(first)
                .andExpect(jsonPath("$.data.documents[0].documentId").value(documentId))
                .andExpect(jsonPath("$.data.documents[0].sourceType").value("DIRECT"))
                .andExpect(jsonPath("$.data.documents[0].currentApproverIds[0]").value(first))
                .andExpect(jsonPath("$.data.documents[0].currentStep").value(1))
                .andExpect(jsonPath("$.data.documents[0].totalSteps").value(2))
                .andExpect(jsonPath("$.data.documents[0].myTurn").value(true));

        // 기안함에서는 기안자 기준 myTurn=false, 현재 결재자는 그대로
        mvc.perform(get(BASE).param("box", "DRAFTED").with(user(drafter)))
                .andExpect(jsonPath("$.data.documents[0].documentId").value(documentId))
                .andExpect(jsonPath("$.data.documents[0].currentApproverIds[0]").value(first))
                .andExpect(jsonPath("$.data.documents[0].myTurn").value(false));
    }

    @Test
    void 반려되면_남은_PENDING_줄이_있어도_대기함과_현재_결재자에서_빠진다() throws Exception {
        long drafter = newEmployee();
        long first = newEmployee();
        long second = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(second, 2, "APPROVER"));

        approve(documentId, first);
        mvc.perform(post(path(documentId, "/reject")).with(user(second))
                        .contentType("application/json").content("{\"comment\":\"반려\"}"))
                .andExpect(status().isOk());

        assertThat(pendingIds(second)).isEmpty();
        mvc.perform(get(BASE).param("box", "DRAFTED").with(user(drafter)))
                .andExpect(jsonPath("$.data.documents[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data.documents[0].currentApproverIds").isEmpty())
                .andExpect(jsonPath("$.data.documents[0].currentStep").isEmpty())
                .andExpect(jsonPath("$.data.documents[0].totalSteps").value(2));
    }

    @Test
    void 대기함은_오래된_문서부터() throws Exception {
        long approver = newEmployee();
        long older = createDocument(newEmployee(), line(approver, 1, "APPROVER"));
        long newer = createDocument(newEmployee(), line(approver, 1, "APPROVER"));

        assertThat(pendingIds(approver)).containsExactly((int) older, (int) newer);
    }

    @Test
    void 관리자도_본인_차례_문서만_보인다() throws Exception {
        long adminId = newEmployee();
        long other = newEmployee();
        createDocument(newEmployee(), line(other, 1, "APPROVER"));
        long mine = createDocument(newEmployee(), line(adminId, 1, "APPROVER"));

        String body = mvc.perform(get(BASE).param("box", "PENDING").with(admin(adminId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$.data.documents[*].documentId");
        assertThat(ids).containsExactly((int) mine);
    }

    @Test
    void 결재상태를_함께_주면_교집합이다() throws Exception {
        long approver = newEmployee();
        long documentId = createDocument(newEmployee(), line(approver, 1, "APPROVER"));

        mvc.perform(get(BASE).param("box", "PENDING").param("approvalStatus", "INPROGRESS").with(user(approver)))
                .andExpect(jsonPath("$.data.documents[0].documentId").value(documentId));
        mvc.perform(get(BASE).param("box", "PENDING").param("approvalStatus", "APPROVED").with(user(approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test
    void 건수는_목록과_같은_조건이다() throws Exception {
        long drafter = newEmployee();
        createDocument(drafter, line(newEmployee(), 1, "APPROVER"));
        createDocument(drafter, line(newEmployee(), 1, "APPROVER"));

        mvc.perform(get(BASE + "/count").param("box", "DRAFTED").with(user(drafter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2));
        mvc.perform(get(BASE + "/count").param("box", "WRONG").with(user(drafter)))
                .andExpect(status().isBadRequest());
    }
}
