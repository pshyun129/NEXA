package com.ssafy.approval_service.domain.document;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 참조함(box=REFERENCE)과 처리함(box=COMPLETED), 각 건수 */
class DocumentReferenceCompletedBoxIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicLong EMP_SEQ = new AtomicLong(500_000);

    private static long newEmployee() {
        return EMP_SEQ.incrementAndGet();
    }

    private List<Integer> box(String box, long who) throws Exception {
        String body = mvc.perform(get(BASE).param("box", box).with(user(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$.data.documents[*].documentId");
        // 건수 API 가 목록과 같은 조건인지 매번 같이 확인한다
        String count = mvc.perform(get(BASE + "/count").param("box", box).with(user(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(count, "$.data.count")).intValue()).isEqualTo(ids.size());
        return ids;
    }

    private void act(long documentId, long who, String action) throws Exception {
        var request = post(path(documentId, "/" + action)).with(user(who));
        if (action.equals("reject")) {
            request.contentType("application/json").content("{\"comment\":\"반려\"}");
        }
        mvc.perform(request).andExpect(status().isOk());
    }

    @Test
    void 참조함에는_참조자와_열람자만_보이고_문서가_끝나도_남는다() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long referrer = newEmployee();
        long viewer = newEmployee();
        long documentId = createDocument(drafter,
                line(approver, 1, "APPROVER"),
                line(referrer, 2, "REFERENCE"),
                line(viewer, 3, "VIEWER"));

        assertThat(box("REFERENCE", referrer)).containsExactly((int) documentId);
        assertThat(box("REFERENCE", viewer)).containsExactly((int) documentId);
        assertThat(box("REFERENCE", approver)).isEmpty();
        assertThat(box("REFERENCE", drafter)).isEmpty();

        act(documentId, approver, "approve");

        assertThat(box("REFERENCE", referrer)).containsExactly((int) documentId);
    }

    @Test
    void 처리함에는_내가_승인하거나_반려한_문서가_들어온다() throws Exception {
        long drafter = newEmployee();
        long first = newEmployee();
        long second = newEmployee();
        long referrer = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(second, 2, "AGREEMENT"),
                line(referrer, 3, "REFERENCE"));

        assertThat(box("COMPLETED", first)).isEmpty();

        act(documentId, first, "approve");
        assertThat(box("COMPLETED", first)).containsExactly((int) documentId);
        assertThat(box("COMPLETED", second)).isEmpty();

        act(documentId, second, "reject");
        assertThat(box("COMPLETED", second)).containsExactly((int) documentId);
        assertThat(box("COMPLETED", referrer)).isEmpty();
        assertThat(box("COMPLETED", drafter)).isEmpty();
    }

    @Test
    void 전결로_생략된_결재자의_처리함에도_들어온다() throws Exception {
        long first = newEmployee();
        long skipped = newEmployee();
        long documentId = createDocument(newEmployee(),
                line(first, 1, "APPROVER"),
                line(skipped, 2, "APPROVER"));

        act(documentId, first, "final-approve");

        assertThat(box("COMPLETED", first)).containsExactly((int) documentId);
        assertThat(box("COMPLETED", skipped)).containsExactly((int) documentId);
        assertThat(box("PENDING", skipped)).isEmpty();
    }

    @Test
    void 참조함은_최신_문서부터_처리함은_최근에_처리한_문서부터() throws Exception {
        long referrer = newEmployee();
        long approver = newEmployee();
        long older = createDocument(newEmployee(), line(approver, 1, "APPROVER"), line(referrer, 2, "REFERENCE"));
        long newer = createDocument(newEmployee(), line(approver, 1, "APPROVER"), line(referrer, 2, "REFERENCE"));
        // 기안 순서와 처리 순서를 반대로: 새 문서를 먼저, 오래된 문서를 나중에 처리
        act(newer, approver, "approve");
        act(older, approver, "approve");

        assertThat(box("REFERENCE", referrer)).containsExactly((int) newer, (int) older);
        assertThat(box("COMPLETED", approver)).containsExactly((int) older, (int) newer);
    }

    @Test
    void 한_문서에_내_줄이_여럿이어도_처리함에는_한_번만_나온다() throws Exception {
        long me = newEmployee();
        long documentId = createDocument(newEmployee(),
                line(me, 1, "APPROVER"),
                line(me, 2, "AGREEMENT"));
        act(documentId, me, "approve");
        act(documentId, me, "approve");

        assertThat(box("COMPLETED", me)).containsExactly((int) documentId);
    }

    @Test
    void 관리자도_본인_문서함만_보인다() throws Exception {
        long adminId = newEmployee();
        long other = newEmployee();
        createDocument(newEmployee(), line(newEmployee(), 1, "APPROVER"), line(other, 2, "REFERENCE"));
        long mine = createDocument(newEmployee(), line(newEmployee(), 1, "APPROVER"), line(adminId, 2, "REFERENCE"));

        String body = mvc.perform(get(BASE).param("box", "REFERENCE").with(admin(adminId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$.data.documents[*].documentId");
        assertThat(ids).containsExactly((int) mine);
    }

    @Test
    void 결재상태를_함께_주면_교집합이다() throws Exception {
        long referrer = newEmployee();
        long approver = newEmployee();
        long inProgress = createDocument(newEmployee(), line(approver, 1, "APPROVER"), line(referrer, 2, "REFERENCE"));
        long approved = createDocument(newEmployee(), line(approver, 1, "APPROVER"), line(referrer, 2, "REFERENCE"));
        act(approved, approver, "approve");

        String body = mvc.perform(get(BASE).param("box", "REFERENCE").param("approvalStatus", "APPROVED")
                        .with(user(referrer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$.data.documents[*].documentId");
        assertThat(ids).containsExactly((int) approved).doesNotContain((int) inProgress);
    }
}
