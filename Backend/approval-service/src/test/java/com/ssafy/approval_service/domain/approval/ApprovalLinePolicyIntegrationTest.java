package com.ssafy.approval_service.domain.approval;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.global.client.UserServiceClient.OrgUnit;
import com.ssafy.approval_service.global.outbox.OutboxEvent;
import com.ssafy.approval_service.global.outbox.OutboxEventRepository;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import com.ssafy.approval_service.support.FakeUserServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.BASE;
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
 * 위임전결 규정으로 결재선을 서버가 만든다 (결재선 생략, 미리보기, 자가결재).
 * 사원 ID 대역 7000번대. 조직:
 * 회사(대표 7900) > 본부(본부장 7800) > 팀(팀장 7700) > 팀원 7001, 7002, 7003
 */
class ApprovalLinePolicyIntegrationTest extends AbstractIntegrationTest {

    private static final long MEMBER = 7001L;
    private static final long TEAM_HEAD = 7700L;
    private static final long DIVISION_HEAD = 7800L;
    private static final long CEO = 7900L;

    @Autowired
    FakeUserServiceClient userService;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void organization() {
        OrgUnit team = new OrgUnit(73L, "개발팀", "TEAM", TEAM_HEAD);
        OrgUnit division = new OrgUnit(72L, "기술본부", "DIVISION", DIVISION_HEAD);
        OrgUnit company = new OrgUnit(71L, "회사", "COMPANY", CEO);
        userService.setOrgChain(MEMBER, team, division, company);
        userService.setOrgChain(TEAM_HEAD, team, division, company);
        userService.setOrgChain(CEO, company);
    }

    @AfterEach
    void restore() {
        userService.reset();
    }

    private String createLeave(long drafter, String attributes) throws Exception {
        return mvc.perform(post(BASE).with(user(drafter)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"휴가 신청","formType":"LEAVE","sourceType":"LEAVE","sourceId":%d,
                         "requestKey":"%s","attributes":%s}"""
                        .formatted(drafter * 10, UUID.randomUUID(), attributes)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static long documentId(String body) {
        return ((Number) JsonPath.read(body, "$.data.documentId")).longValue();
    }

    @Test
    void 결재선을_생략하면_휴가_일수로_전결_단계를_정해_결재선을_만든다() throws Exception {
        long docId = documentId(createLeave(MEMBER, """
                {"days":5}"""));

        mvc.perform(get(BASE + "/" + docId).with(user(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INPROGRESS"))
                .andExpect(jsonPath("$.data.approvalLevel").value("DIVISION"))
                .andExpect(jsonPath("$.data.attributes.days").value(5))
                // 보낸 값만 돌려준다 (없는 값·내부 메서드는 JSON 에 없음)
                .andExpect(jsonPath("$.data.attributes.amount").doesNotExist())
                .andExpect(jsonPath("$.data.attributes.empty").doesNotExist())
                .andExpect(jsonPath("$.data.approvalLines.length()").value(2))
                .andExpect(jsonPath("$.data.approvalLines[0].empId").value(TEAM_HEAD))
                .andExpect(jsonPath("$.data.approvalLines[0].step").value(1))
                .andExpect(jsonPath("$.data.approvalLines[0].role").value("APPROVER"))
                .andExpect(jsonPath("$.data.approvalLines[1].empId").value(DIVISION_HEAD))
                .andExpect(jsonPath("$.data.approvalLines[1].step").value(2));
    }

    @Test
    void 만든_결재선으로_그대로_결재가_진행된다() throws Exception {
        long docId = documentId(createLeave(MEMBER, """
                {"days":1}"""));

        // 짧은 휴가는 팀장 전결 → 팀장 승인으로 끝
        mvc.perform(post(path(docId, "/approve")).with(user(TEAM_HEAD))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + docId).with(user(MEMBER)))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvalLevel").value("TEAM"));
    }

    @Test
    void 팀장이_기안하면_본부장_전결() throws Exception {
        long docId = documentId(createLeave(TEAM_HEAD, "null"));

        mvc.perform(get(BASE + "/" + docId).with(user(TEAM_HEAD)))
                .andExpect(jsonPath("$.data.approvalLevel").value("TEAM"))
                .andExpect(jsonPath("$.data.attributes").doesNotExist())
                .andExpect(jsonPath("$.data.approvalLines.length()").value(1))
                .andExpect(jsonPath("$.data.approvalLines[0].empId").value(DIVISION_HEAD));
    }

    @Test
    void 대표이사가_기안하면_상신과_함께_승인되고_결과_이벤트가_남는다() throws Exception {
        long docId = documentId(createLeave(CEO, """
                {"days":3}"""));

        mvc.perform(get(BASE + "/" + docId).with(user(CEO)))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.approvalLines.length()").value(0))
                .andExpect(jsonPath("$.data.histories[0].action").value("SUBMIT"))
                .andExpect(jsonPath("$.data.histories[1].action").value("FINAL_APPROVE"))
                .andExpect(jsonPath("$.data.histories[1].actorId").value(CEO));

        List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                .filter(e -> e.getMessageKey().equals(String.valueOf(docId)))
                .toList();
        assertThat(events).hasSize(1);
        assertThat((String) JsonPath.read(events.getFirst().getPayload(), "$.status")).isEqualTo("APPROVED");
    }

    @Test
    void 요청에_결재선을_보내도_무시하고_규정으로_만든다() throws Exception {
        // 예전 형식 요청 (attendance 등). 400 으로 깨지지 않고 결재선만 무시된다
        String body = mvc.perform(post(BASE).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"일반","formType":"GENERAL","attributes":{"amount":50000000},"approvalLines":[%s]}"""
                        .formatted(line(7002L, 1, "APPROVER"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get(BASE + "/" + documentId(body)).with(user(MEMBER)))
                .andExpect(jsonPath("$.data.approvalLevel").value("COMPANY"))
                .andExpect(jsonPath("$.data.approvalLines[*].empId")
                        .value(org.hamcrest.Matchers.contains((int) TEAM_HEAD, (int) DIVISION_HEAD, (int) CEO)))
                .andExpect(jsonPath("$.data.approvalLines[*].required")
                        .value(org.hamcrest.Matchers.contains(true, true, true)));
    }

    // ===== 규정 강제: 규정상 결재자는 빼거나 순서를 바꾸거나 전결로 건너뛸 수 없다 =====

    private long createGeneral(long amount) throws Exception {
        return documentId(mvc.perform(post(BASE).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"일반","formType":"GENERAL","attributes":{"amount":%d}}""".formatted(amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions reorder(long docId, long... empIds) throws Exception {
        return mvc.perform(patch(path(docId, "/approvers/order")).with(user(MEMBER))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"empIds":[%s]}""".formatted(Arrays.stream(empIds).mapToObj(String::valueOf)
                        .collect(Collectors.joining(",")))));
    }

    @Test
    void 규정상_결재자는_뺄_수_없다() throws Exception {
        long docId = createGeneral(2_000_000);   // 팀장 → 본부장

        mvc.perform(delete(path(docId, "/approvers/" + DIVISION_HEAD)).with(user(MEMBER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_024"));
    }

    @Test
    void 추가한_결재자는_빼거나_옮길_수_있지만_규정상_결재자끼리_순서는_못_바꾼다() throws Exception {
        long docId = createGeneral(2_000_000);   // 팀장(1) → 본부장(2)
        long added = 7002L;
        mvc.perform(post(path(docId, "/approvers")).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                        .content(line(added, 3, "AGREEMENT")))
                .andExpect(status().isCreated());

        // 추가한 합의자를 맨 앞으로: 규정상 결재자끼리 순서(팀장 → 본부장)는 그대로라 가능
        reorder(docId, added, TEAM_HEAD, DIVISION_HEAD).andExpect(status().isOk());
        // 규정상 결재자끼리 순서를 뒤집으면 409
        reorder(docId, added, DIVISION_HEAD, TEAM_HEAD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_024"));
        // 추가한 결재자는 뺄 수 있다
        mvc.perform(delete(path(docId, "/approvers/" + added)).with(user(MEMBER))).andExpect(status().isOk());

        mvc.perform(get(BASE + "/" + docId).with(user(MEMBER)))
                .andExpect(jsonPath("$.data.approvalLines[*].empId")
                        .value(org.hamcrest.Matchers.contains((int) TEAM_HEAD, (int) DIVISION_HEAD)));
    }

    @Test
    void 규정상_결재자를_건너뛰는_전결은_안_되고_추가한_결재자만_건너뛸_수_있다() throws Exception {
        long docId = createGeneral(2_000_000);   // 팀장(1) → 본부장(2)
        long added = 7002L;
        mvc.perform(post(path(docId, "/approvers")).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                        .content(line(added, 3, "APPROVER")))
                .andExpect(status().isCreated());

        // 팀장이 전결하면 본부장(규정상 전결권자)을 건너뛰게 된다 → 409
        mvc.perform(post(path(docId, "/final-approve")).with(user(TEAM_HEAD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_025"));

        // 팀장 승인 → 본부장이 전결하면 기안자가 추가한 결재자(added)만 생략되고 끝난다
        mvc.perform(post(path(docId, "/approve")).with(user(TEAM_HEAD))).andExpect(status().isOk());
        mvc.perform(post(path(docId, "/final-approve")).with(user(DIVISION_HEAD))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + docId).with(user(MEMBER)))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvalLines[2].action").value("SKIPPED"));
    }

    @Test
    void 재기안하면_지금_조직도와_규정으로_결재선을_다시_만든다() throws Exception {
        long docId = createGeneral(2_000_000);   // 팀장 → 본부장
        mvc.perform(post(path(docId, "/reject")).with(user(TEAM_HEAD)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"comment":"보완"}"""))
                .andExpect(status().isOk());
        // 그 사이 팀장이 7003 으로 바뀌었다
        userService.setOrgChain(MEMBER, new OrgUnit(73L, "개발팀", "TEAM", 7003L),
                new OrgUnit(72L, "기술본부", "DIVISION", DIVISION_HEAD), new OrgUnit(71L, "회사", "COMPANY", CEO));

        mvc.perform(post(path(docId, "/resubmit")).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"일반 (재기안)"}"""))
                .andExpect(status().isOk());

        mvc.perform(get(BASE + "/" + docId).with(user(MEMBER)))
                .andExpect(jsonPath("$.data.status").value("INPROGRESS"))
                .andExpect(jsonPath("$.data.approvalLines[*].empId")
                        .value(org.hamcrest.Matchers.contains(7003, (int) DIVISION_HEAD)))
                .andExpect(jsonPath("$.data.approvalLines[*].required")
                        .value(org.hamcrest.Matchers.contains(true, true)));
    }

    @Test
    void 미리보기는_저장하지_않고_기본_결재선을_보여준다() throws Exception {
        mvc.perform(get(BASE + "/approval-line-preview").with(user(MEMBER))
                        .param("formType", "GENERAL").param("amount", "20000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.formType").value("GENERAL"))
                .andExpect(jsonPath("$.data.approvalLevel").value("COMPANY"))
                .andExpect(jsonPath("$.data.selfApproval").value(false))
                .andExpect(jsonPath("$.data.approvalLines[*].empId")
                        .value(org.hamcrest.Matchers.contains((int) TEAM_HEAD, (int) DIVISION_HEAD, (int) CEO)));

        mvc.perform(get(BASE + "/approval-line-preview").with(user(MEMBER))
                        .param("formType", "BUSINESS_TRIP").param("overseas", "true"))
                .andExpect(jsonPath("$.data.approvalLevel").value("DIVISION"))
                .andExpect(jsonPath("$.data.approvalLines.length()").value(2));

        mvc.perform(get(BASE + "/approval-line-preview").with(user(CEO)).param("formType", "GENERAL"))
                .andExpect(jsonPath("$.data.selfApproval").value(true))
                .andExpect(jsonPath("$.data.approvalLines.length()").value(0));
    }

    @Test
    void 신청_값이_음수면_400() throws Exception {
        mvc.perform(post(BASE).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"휴가","formType":"LEAVE","sourceType":"LEAVE","sourceId":1,
                         "requestKey":"%s","attributes":{"days":-1}}""".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));

        mvc.perform(get(BASE + "/approval-line-preview").with(user(MEMBER))
                        .param("formType", "GENERAL").param("amount", "-5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 조직도_조회가_안_되면_503이고_문서를_만들지_않는다() throws Exception {
        userService.setDown(true);
        long before = outboxEventRepository.count();

        mvc.perform(post(BASE).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"휴가","formType":"LEAVE","sourceType":"LEAVE","sourceId":70019,
                         "requestKey":"%s"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_017"));
        assertThat(outboxEventRepository.count()).isEqualTo(before);
    }

    @Test
    void 결재자를_정할_수_없으면_409() throws Exception {
        userService.setOrgChain(7003L, new OrgUnit(74L, "공석팀", "TEAM", null), new OrgUnit(71L, "회사", "COMPANY", null));

        mvc.perform(post(BASE).with(user(7003L)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"일반","formType":"GENERAL"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_022"));
    }
}
