package com.ssafy.approval_service.domain.approval;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.admin;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.line;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.path;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결재 흐름(승인, 반려, 전결, 회수, 재기안)과 결재선 변경.
 * 컨테이너 DB 를 모든 테스트가 공유하므로 테스트마다 겹치지 않는 사원 ID 로 자기 문서를 만든다
 * (조회 권한 테스트는 1_000번대, 이 테스트는 100_000번대).
 */
class ApprovalFlowIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicLong EMP_SEQ = new AtomicLong(100_000);

    private static long newEmployee() {
        return EMP_SEQ.incrementAndGet();
    }

    // ===== 요청 =====

    private ResultActions approve(long documentId, long who) throws Exception {
        return mvc.perform(post(path(documentId, "/approve")).with(user(who)));
    }

    private ResultActions reject(long documentId, long who, String comment) throws Exception {
        return mvc.perform(post(path(documentId, "/reject")).with(user(who))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"comment":"%s"}""".formatted(comment)));
    }

    private ResultActions finalApprove(long documentId, long who) throws Exception {
        return mvc.perform(post(path(documentId, "/final-approve")).with(user(who)));
    }

    private ResultActions cancel(long documentId, long who) throws Exception {
        return mvc.perform(post(path(documentId, "/cancel")).with(user(who)));
    }

    private ResultActions resubmit(long documentId, long who, String title) throws Exception {
        return mvc.perform(post(path(documentId, "/resubmit")).with(user(who))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"%s"}""".formatted(title)));
    }

    private ResultActions addApprover(long documentId, long who, String line) throws Exception {
        return mvc.perform(post(path(documentId, "/approvers")).with(user(who))
                .contentType(MediaType.APPLICATION_JSON).content(line));
    }

    private ResultActions removeApprover(long documentId, long who, long empId) throws Exception {
        return mvc.perform(delete(path(documentId, "/approvers/" + empId)).with(user(who)));
    }

    private ResultActions reorder(long documentId, long who, long... empIds) throws Exception {
        String ids = Arrays.stream(empIds).mapToObj(String::valueOf).collect(Collectors.joining(","));
        return mvc.perform(patch(path(documentId, "/approvers/order")).with(user(who))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"empIds":[%s]}""".formatted(ids)));
    }

    /** sub: /referrers 또는 /viewers */
    private ResultActions addParticipant(long documentId, String sub, long who, long empId) throws Exception {
        return mvc.perform(post(path(documentId, sub)).with(user(who))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"empId":%d}""".formatted(empId)));
    }

    private ResultActions removeParticipant(long documentId, String sub, long who, long empId) throws Exception {
        return mvc.perform(delete(path(documentId, sub + "/" + empId)).with(user(who)));
    }

    private static void expectError(ResultActions result, HttpStatus status, String code) throws Exception {
        result.andExpect(status().is(status.value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    // ===== 조회 (관리자 권한으로) =====

    private String read(String path) throws Exception {
        return mvc.perform(get(path).with(admin(newEmployee())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String documentStatus(long documentId) throws Exception {
        return JsonPath.read(read(path(documentId, "")), "$.data.status");
    }

    private String title(long documentId) throws Exception {
        return JsonPath.read(read(path(documentId, "")), "$.data.title");
    }

    /** 결재자/합의자의 사원 ID (step 순) */
    private List<Long> approverIds(long documentId) throws Exception {
        List<Number> ids = JsonPath.read(read(path(documentId, "/approvers")), "$.data[*].empId");
        return ids.stream().map(Number::longValue).toList();
    }

    /** 결재자/합의자의 처리 상태 (step 순) */
    private List<String> approverActions(long documentId) throws Exception {
        return JsonPath.read(read(path(documentId, "/approvers")), "$.data[*].action");
    }

    private List<Long> participantIds(long documentId, String sub) throws Exception {
        List<Number> ids = JsonPath.read(read(path(documentId, sub)), "$.data[*].empId");
        return ids.stream().map(Number::longValue).toList();
    }

    private List<Map<String, Object>> histories(long documentId) throws Exception {
        return JsonPath.read(read(path(documentId, "/histories")), "$.data");
    }

    private List<String> historyActions(long documentId) throws Exception {
        return histories(documentId).stream().map(h -> (String) h.get("action")).toList();
    }

    private Map<String, Object> lastHistory(long documentId) throws Exception {
        List<Map<String, Object>> histories = histories(documentId);
        return histories.get(histories.size() - 1);
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    // ===== 승인 =====

    @Test
    void 순서대로_승인하면_마지막_결재자_승인에서_최종_승인() throws Exception {
        long drafter = newEmployee();
        long first = newEmployee();
        long agreement = newEmployee();
        long last = newEmployee();
        long referrer = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(agreement, 2, "AGREEMENT"),
                line(last, 3, "APPROVER"),
                line(referrer, 4, "REFERENCE"));

        approve(documentId, first).andExpect(status().isOk());
        assertThat(documentStatus(documentId)).isEqualTo("INPROGRESS");
        approve(documentId, agreement).andExpect(status().isOk());
        assertThat(documentStatus(documentId)).isEqualTo("INPROGRESS");
        approve(documentId, last).andExpect(status().isOk());

        assertThat(documentStatus(documentId)).isEqualTo("APPROVED");
        assertThat(approverActions(documentId)).containsExactly("AGREE", "AGREE", "AGREE");
        assertThat(historyActions(documentId)).containsExactly("SUBMIT", "APPROVE", "APPROVE", "APPROVE");
        assertThat(asLong(lastHistory(documentId).get("actorId"))).isEqualTo(last);
    }

    @Test
    void 차례가_아니면_승인_반려_전결_403_APPROVAL_012() throws Exception {
        long drafter = newEmployee();
        long first = newEmployee();
        long second = newEmployee();
        long referrer = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(second, 2, "APPROVER"),
                line(referrer, 3, "REFERENCE"));

        expectError(approve(documentId, second), HttpStatus.FORBIDDEN, "APPROVAL_012");
        expectError(reject(documentId, second, "순서 무시"), HttpStatus.FORBIDDEN, "APPROVAL_012");
        expectError(finalApprove(documentId, second), HttpStatus.FORBIDDEN, "APPROVAL_012");
        expectError(approve(documentId, referrer), HttpStatus.FORBIDDEN, "APPROVAL_012");
        expectError(approve(documentId, newEmployee()), HttpStatus.FORBIDDEN, "APPROVAL_012");

        assertThat(approverActions(documentId)).containsExactly("PENDING", "PENDING");
        assertThat(historyActions(documentId)).containsExactly("SUBMIT");
    }

    @Test
    void 완료된_문서는_승인_반려_409_APPROVAL_006() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long documentId = createDocument(drafter, line(approver, 1, "APPROVER"));
        approve(documentId, approver).andExpect(status().isOk());

        expectError(approve(documentId, approver), HttpStatus.CONFLICT, "APPROVAL_006");
        expectError(reject(documentId, approver, "다시 반려"), HttpStatus.CONFLICT, "APPROVAL_006");
        expectError(finalApprove(documentId, approver), HttpStatus.CONFLICT, "APPROVAL_006");
        assertThat(documentStatus(documentId)).isEqualTo("APPROVED");
    }

    // ===== 반려 =====

    @Test
    void 합의자가_비동의하면_반려되고_이력에_사유가_남음() throws Exception {
        long drafter = newEmployee();
        long agreement = newEmployee();
        long approver = newEmployee();
        long documentId = createDocument(drafter,
                line(agreement, 1, "AGREEMENT"),
                line(approver, 2, "APPROVER"));

        reject(documentId, agreement, "예산 초과").andExpect(status().isOk());

        assertThat(documentStatus(documentId)).isEqualTo("REJECTED");
        assertThat(approverActions(documentId)).containsExactly("DISAGREE", "PENDING");
        Map<String, Object> last = lastHistory(documentId);
        assertThat(last.get("action")).isEqualTo("REJECT");
        assertThat(last.get("comment")).isEqualTo("예산 초과");
        assertThat(asLong(last.get("actorId"))).isEqualTo(agreement);
        expectError(approve(documentId, approver), HttpStatus.CONFLICT, "APPROVAL_006");
    }

    @Test
    void 반려_사유가_비어_있으면_400_COMMON_001() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long documentId = createDocument(drafter, line(approver, 1, "APPROVER"));

        expectError(reject(documentId, approver, ""), HttpStatus.BAD_REQUEST, "COMMON_001");
        assertThat(documentStatus(documentId)).isEqualTo("INPROGRESS");
    }

    // ===== 전결 =====

    @Test
    void 전결하면_남은_결재선은_SKIPPED_문서는_최종_승인() throws Exception {
        long drafter = newEmployee();
        long first = newEmployee();
        long agreement = newEmployee();
        long last = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(agreement, 2, "AGREEMENT"),
                line(last, 3, "APPROVER"));

        finalApprove(documentId, first).andExpect(status().isOk());

        assertThat(documentStatus(documentId)).isEqualTo("APPROVED");
        assertThat(approverActions(documentId)).containsExactly("AGREE", "SKIPPED", "SKIPPED");
        assertThat(historyActions(documentId)).containsExactly("SUBMIT", "FINAL_APPROVE");
    }

    @Test
    void 합의자는_전결_403_APPROVAL_013() throws Exception {
        long drafter = newEmployee();
        long first = newEmployee();
        long agreement = newEmployee();
        long last = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(agreement, 2, "AGREEMENT"),
                line(last, 3, "APPROVER"));
        approve(documentId, first).andExpect(status().isOk());

        expectError(finalApprove(documentId, agreement), HttpStatus.FORBIDDEN, "APPROVAL_013");
        assertThat(approverActions(documentId)).containsExactly("AGREE", "PENDING", "PENDING");
    }

    // ===== 회수 =====

    @Test
    void 아무도_처리하지_않았으면_기안자가_회수() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long documentId = createDocument(drafter, line(approver, 1, "APPROVER"));

        cancel(documentId, drafter).andExpect(status().isOk());

        assertThat(documentStatus(documentId)).isEqualTo("CANCEL");
        assertThat(historyActions(documentId)).containsExactly("SUBMIT", "CANCEL");
        expectError(approve(documentId, approver), HttpStatus.CONFLICT, "APPROVAL_006");
    }

    @Test
    void 기안자가_아니거나_결재가_진행됐으면_회수_불가() throws Exception {
        long drafter = newEmployee();
        long first = newEmployee();
        long second = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(second, 2, "APPROVER"));

        expectError(cancel(documentId, first), HttpStatus.FORBIDDEN, "APPROVAL_005");

        approve(documentId, first).andExpect(status().isOk());
        expectError(cancel(documentId, drafter), HttpStatus.CONFLICT, "APPROVAL_008");
        assertThat(documentStatus(documentId)).isEqualTo("INPROGRESS");
    }

    // ===== 재기안 =====

    @Test
    void 반려된_문서를_재기안하면_지금_조직도로_결재선을_다시_만들고_다음_차수_진행() throws Exception {
        long drafter = newEmployee();
        long firstApprover = newEmployee();
        long secondApprover = newEmployee();
        long documentId = createDocument(drafter, line(firstApprover, 1, "APPROVER"));
        reject(documentId, firstApprover, "금액 확인 필요").andExpect(status().isOk());
        // 그 사이 팀장이 바뀌었다
        assignTeamHead(drafter, secondApprover);

        resubmit(documentId, drafter, "결재 흐름 테스트 (재기안)").andExpect(status().isOk());

        assertThat(documentStatus(documentId)).isEqualTo("INPROGRESS");
        assertThat(title(documentId)).isEqualTo("결재 흐름 테스트 (재기안)");
        assertThat(approverIds(documentId)).containsExactly(secondApprover);
        Map<String, Object> resubmitted = lastHistory(documentId);
        assertThat(resubmitted.get("action")).isEqualTo("RESUBMIT");
        assertThat(resubmitted.get("round")).isEqualTo(2);

        expectError(approve(documentId, firstApprover), HttpStatus.FORBIDDEN, "APPROVAL_012");
        approve(documentId, secondApprover).andExpect(status().isOk());

        assertThat(documentStatus(documentId)).isEqualTo("APPROVED");
        assertThat(historyActions(documentId)).containsExactly("SUBMIT", "REJECT", "RESUBMIT", "APPROVE");
        assertThat(histories(documentId)).extracting(h -> h.get("round")).containsExactly(1, 1, 2, 2);
    }

    @Test
    void 재기안_거부_진행_중_문서_기안자_아님() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long documentId = createDocument(drafter, line(approver, 1, "APPROVER"));

        expectError(resubmit(documentId, drafter, "진행 중 재기안"), HttpStatus.CONFLICT, "APPROVAL_014");

        reject(documentId, approver, "보완 필요").andExpect(status().isOk());
        expectError(resubmit(documentId, approver, "남의 문서 재기안"), HttpStatus.FORBIDDEN, "APPROVAL_005");

        assertThat(documentStatus(documentId)).isEqualTo("REJECTED");
        assertThat(approverIds(documentId)).containsExactly(approver);
    }

    // ===== 결재선 변경: 결재자 =====

    @Test
    void 결재자를_추가하면_이력이_남고_추가된_결재자_차례까지_진행() throws Exception {
        long drafter = newEmployee();
        long first = newEmployee();
        long second = newEmployee();
        long added = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(second, 2, "APPROVER"));
        approve(documentId, first).andExpect(status().isOk());

        addApprover(documentId, drafter, line(added, 3, "AGREEMENT")).andExpect(status().isCreated());

        assertThat(approverIds(documentId)).containsExactly(first, second, added);
        Map<String, Object> last = lastHistory(documentId);
        assertThat(last.get("action")).isEqualTo("APPROVER_ADD");
        assertThat(asLong(last.get("targetEmpId"))).isEqualTo(added);

        approve(documentId, second).andExpect(status().isOk());
        assertThat(documentStatus(documentId)).isEqualTo("INPROGRESS");
        approve(documentId, added).andExpect(status().isOk());
        assertThat(documentStatus(documentId)).isEqualTo("APPROVED");
    }

    @Test
    void 결재자_추가_거부_규칙() throws Exception {
        long drafter = newEmployee();
        long acted = newEmployee();
        long pending = newEmployee();
        long referrer = newEmployee();
        long documentId = createDocument(drafter,
                line(acted, 2, "APPROVER"),
                line(pending, 4, "APPROVER"),
                line(referrer, 5, "REFERENCE"));
        approve(documentId, acted).andExpect(status().isOk());

        // 결재가 끝난 순서(2) 앞
        expectError(addApprover(documentId, drafter, line(newEmployee(), 1, "APPROVER")),
                HttpStatus.BAD_REQUEST, "APPROVAL_010");
        // 이미 있는 step
        expectError(addApprover(documentId, drafter, line(newEmployee(), 4, "APPROVER")),
                HttpStatus.BAD_REQUEST, "APPROVAL_004");
        // 이미 결재선에 있는 사원 (결재자, 참조자)
        expectError(addApprover(documentId, drafter, line(pending, 6, "APPROVER")),
                HttpStatus.BAD_REQUEST, "APPROVAL_003");
        expectError(addApprover(documentId, drafter, line(referrer, 6, "APPROVER")),
                HttpStatus.BAD_REQUEST, "APPROVAL_003");
        // 기안자 본인
        expectError(addApprover(documentId, drafter, line(drafter, 6, "APPROVER")),
                HttpStatus.BAD_REQUEST, "APPROVAL_002");
        // 결재자 API로 참조자 추가
        expectError(addApprover(documentId, drafter, line(newEmployee(), 6, "REFERENCE")),
                HttpStatus.BAD_REQUEST, "APPROVAL_009");
        // 기안자가 아닌 사람
        expectError(addApprover(documentId, pending, line(newEmployee(), 6, "APPROVER")),
                HttpStatus.FORBIDDEN, "APPROVAL_005");

        assertThat(approverIds(documentId)).containsExactly(acted, pending);
    }

    @Test
    void 결재자_제거_거부_규칙() throws Exception {
        long drafter = newEmployee();
        long only = newEmployee();
        long referrer = newEmployee();
        long single = createDocument(drafter,
                line(only, 1, "APPROVER"),
                line(referrer, 2, "REFERENCE"));
        // 마지막 결재자
        expectError(removeApprover(single, drafter, only), HttpStatus.BAD_REQUEST, "APPROVAL_011");
        // 결재자 API로 참조자 제거
        expectError(removeApprover(single, drafter, referrer), HttpStatus.NOT_FOUND, "APPROVAL_007");

        long first = newEmployee();
        long second = newEmployee();
        long documentId = createDocument(drafter,
                line(first, 1, "APPROVER"),
                line(second, 2, "APPROVER"));
        // 기안자가 아닌 사람
        expectError(removeApprover(documentId, second, first), HttpStatus.FORBIDDEN, "APPROVAL_005");
        approve(documentId, first).andExpect(status().isOk());
        // 이미 결재한 결재자
        expectError(removeApprover(documentId, drafter, first), HttpStatus.CONFLICT, "APPROVAL_008");
        // 참조자 API로 결재자 제거
        expectError(removeParticipant(documentId, "/referrers", drafter, second), HttpStatus.NOT_FOUND, "APPROVAL_007");

        assertThat(approverIds(documentId)).containsExactly(first, second);
    }

    @Test
    void 결재자_순서를_바꾸면_차례도_바뀜() throws Exception {
        long drafter = newEmployee();
        long a = newEmployee();
        long b = newEmployee();
        long c = newEmployee();
        long documentId = createDocument(drafter,
                line(a, 1, "APPROVER"),
                line(b, 2, "APPROVER"),
                line(c, 3, "APPROVER"));

        reorder(documentId, drafter, b, a, c).andExpect(status().isOk());

        assertThat(approverIds(documentId)).containsExactly(b, a, c);
        expectError(approve(documentId, a), HttpStatus.FORBIDDEN, "APPROVAL_012");
        approve(documentId, b).andExpect(status().isOk());

        // 이미 결재한 b의 자리를 옮김
        expectError(reorder(documentId, drafter, a, b, c), HttpStatus.CONFLICT, "APPROVAL_008");
        // 누락, 중복, 결재선에 없는 사원
        expectError(reorder(documentId, drafter, b, a), HttpStatus.BAD_REQUEST, "APPROVAL_010");
        expectError(reorder(documentId, drafter, b, a, a), HttpStatus.BAD_REQUEST, "APPROVAL_010");
        expectError(reorder(documentId, drafter, b, a, newEmployee()), HttpStatus.BAD_REQUEST, "APPROVAL_010");
        // 기안자가 아닌 사람
        expectError(reorder(documentId, a, b, c, a), HttpStatus.FORBIDDEN, "APPROVAL_005");

        assertThat(approverIds(documentId)).containsExactly(b, a, c);
    }

    // ===== 결재선 변경: 참조자, 열람자 =====

    @Test
    void 참조자와_열람자_추가_제거() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long referrer = newEmployee();
        long viewer = newEmployee();
        long documentId = createDocument(drafter, line(approver, 1, "APPROVER"));

        addParticipant(documentId, "/referrers", drafter, referrer).andExpect(status().isCreated());
        addParticipant(documentId, "/viewers", drafter, viewer).andExpect(status().isCreated());
        assertThat(participantIds(documentId, "/referrers")).containsExactly(referrer);
        assertThat(participantIds(documentId, "/viewers")).containsExactly(viewer);

        // 한 문서에서 한 사원은 역할 하나만
        expectError(addParticipant(documentId, "/viewers", drafter, referrer), HttpStatus.BAD_REQUEST, "APPROVAL_003");
        expectError(addParticipant(documentId, "/referrers", drafter, drafter), HttpStatus.BAD_REQUEST, "APPROVAL_002");
        // 기안자가 아닌 사람
        expectError(addParticipant(documentId, "/viewers", approver, newEmployee()), HttpStatus.FORBIDDEN, "APPROVAL_005");
        // 역할이 다른 API로 제거
        expectError(removeParticipant(documentId, "/referrers", drafter, viewer), HttpStatus.NOT_FOUND, "APPROVAL_007");

        removeParticipant(documentId, "/referrers", drafter, referrer).andExpect(status().isOk());
        assertThat(participantIds(documentId, "/referrers")).isEmpty();
        assertThat(participantIds(documentId, "/viewers")).containsExactly(viewer);

        // 완료된 문서는 변경 불가
        approve(documentId, approver).andExpect(status().isOk());
        expectError(addParticipant(documentId, "/viewers", drafter, newEmployee()), HttpStatus.CONFLICT, "APPROVAL_006");
        expectError(removeParticipant(documentId, "/viewers", drafter, viewer), HttpStatus.CONFLICT, "APPROVAL_006");
    }
}
