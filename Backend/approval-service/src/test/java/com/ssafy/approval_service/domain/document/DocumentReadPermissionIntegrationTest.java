package com.ssafy.approval_service.domain.document;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.BASE;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.admin;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.line;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문서 조회 권한. 관리자, 기안자, 현재 결재선 참여자만 조회할 수 있고, 그 외에는 없는 문서와 같은 404.
 * 컨테이너 DB 를 모든 테스트가 공유하므로 테스트마다 겹치지 않는 사원 ID 로 자기 문서를 만든다.
 */
class DocumentReadPermissionIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicLong EMP_SEQ = new AtomicLong(1_000);

    /** 기안자 1명 + 결재자/합의자/참조자/열람자 1명씩인 문서 */
    private record Fixture(long documentId, long drafter, long approver, long agreement, long referrer, long viewer) {
    }

    private static long newEmployee() {
        return EMP_SEQ.incrementAndGet();
    }

    private static RequestPostProcessor withoutRole(long empId) {
        return jwt().jwt(j -> j.subject(String.valueOf(empId)).claim("type", "access"));
    }

    private Fixture createFixture() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long agreement = newEmployee();
        long referrer = newEmployee();
        long viewer = newEmployee();
        long documentId = createDocument(drafter,
                line(approver, 1, "APPROVER"),
                line(agreement, 2, "AGREEMENT"),
                line(referrer, 3, "REFERENCE"),
                line(viewer, 4, "VIEWER"));
        return new Fixture(documentId, drafter, approver, agreement, referrer, viewer);
    }

    private static List<String> readPaths(long documentId) {
        String doc = BASE + "/" + documentId;
        return List.of(doc, doc + "/histories", doc + "/approvers", doc + "/referrers", doc + "/viewers");
    }

    private void expectReadable(long documentId, RequestPostProcessor who) throws Exception {
        for (String path : readPaths(documentId)) {
            mvc.perform(get(path).with(who))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    private void expectNotFound(long documentId, RequestPostProcessor who) throws Exception {
        for (String path : readPaths(documentId)) {
            mvc.perform(get(path).with(who))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("APPROVAL_001"));
        }
    }

    private List<Long> listedIds(RequestPostProcessor who, String query) throws Exception {
        MvcResult result = mvc.perform(get(BASE + query).with(who))
                .andExpect(status().isOk())
                .andReturn();
        List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.data.documents[*].documentId");
        return ids.stream().map(Number::longValue).toList();
    }

    // ===== 상세, 이력, 결재선 조회 =====

    @Test
    void 기안자와_결재선_참여자는_모든_조회_API_200() throws Exception {
        Fixture f = createFixture();
        expectReadable(f.documentId(), user(f.drafter()));
        expectReadable(f.documentId(), user(f.approver()));
        expectReadable(f.documentId(), user(f.agreement()));
        expectReadable(f.documentId(), user(f.referrer()));
        expectReadable(f.documentId(), user(f.viewer()));
    }

    @Test
    void 관리자는_관계없는_문서도_200() throws Exception {
        Fixture f = createFixture();
        expectReadable(f.documentId(), admin(newEmployee()));
    }

    @Test
    void 관계없는_사원은_모든_조회_API_404_APPROVAL_001() throws Exception {
        Fixture f = createFixture();
        expectNotFound(f.documentId(), user(newEmployee()));
    }

    @Test
    void 권한_없는_문서와_없는_문서는_같은_응답() throws Exception {
        Fixture f = createFixture();
        long stranger = newEmployee();
        String forbidden = mvc.perform(get(BASE + "/" + f.documentId()).with(user(stranger)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get(BASE + "/" + Long.MAX_VALUE).with(user(stranger)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(forbidden).isEqualTo(missing);
    }

    @Test
    void 없는_문서의_이력도_404() throws Exception {
        // 이전에는 문서 존재 확인 없이 빈 배열을 돌려줬다
        mvc.perform(get(BASE + "/" + Long.MAX_VALUE + "/histories").with(admin(newEmployee())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_001"));
    }

    @Test
    void role_클레임이_없으면_일반_사원으로_처리() throws Exception {
        Fixture f = createFixture();
        expectReadable(f.documentId(), withoutRole(f.drafter()));
        expectNotFound(f.documentId(), withoutRole(newEmployee()));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        Fixture f = createFixture();
        mvc.perform(get(BASE + "/" + f.documentId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
    }

    @Test
    void 쿠키만_있고_Bearer_헤더가_없으면_401() throws Exception {
        // 쿠키 → Bearer 변환은 gateway 가 한다. 하위 서비스는 쿠키를 읽지 않는다
        Fixture f = createFixture();
        mvc.perform(get(BASE + "/" + f.documentId()).cookie(new Cookie("accessToken", "any-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_001"));
    }

    // ===== 결재선에서 빠진 사람 =====

    @Test
    void 결재선에서_제거된_결재자는_404() throws Exception {
        long drafter = newEmployee();
        long kept = newEmployee();
        long removed = newEmployee();
        long documentId = createDocument(drafter, line(kept, 1, "APPROVER"), line(removed, 2, "APPROVER"));
        expectReadable(documentId, user(removed));

        mvc.perform(delete(BASE + "/" + documentId + "/approvers/" + removed).with(user(drafter)))
                .andExpect(status().isOk());

        expectNotFound(documentId, user(removed));
        expectReadable(documentId, user(kept));
    }

    @Test
    void 재기안으로_결재선에서_빠진_이전_차수_결재자는_404() throws Exception {
        long drafter = newEmployee();
        long firstApprover = newEmployee();
        long secondApprover = newEmployee();
        long documentId = createDocument(drafter, line(firstApprover, 1, "APPROVER"));

        mvc.perform(post(BASE + "/" + documentId + "/reject").with(user(firstApprover))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"comment":"금액 확인 필요"}"""))
                .andExpect(status().isOk());
        expectReadable(documentId, user(firstApprover));    // 반려만으로는 결재선이 지워지지 않는다

        // 그 사이 팀장이 바뀌어 재기안 결재선은 새 팀장으로 만들어진다
        assignTeamHead(drafter, secondApprover);
        mvc.perform(post(BASE + "/" + documentId + "/resubmit").with(user(drafter))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"권한 테스트 (재기안)"}"""))
                .andExpect(status().isOk());

        expectNotFound(documentId, user(firstApprover));
        expectReadable(documentId, user(secondApprover));
    }

    // ===== 목록 =====

    @Test
    void 목록은_기안했거나_결재선에_있는_문서만() throws Exception {
        Fixture mine = createFixture();
        Fixture other = createFixture();

        assertThat(listedIds(user(mine.drafter()), ""))
                .contains(mine.documentId()).doesNotContain(other.documentId());
        assertThat(listedIds(user(mine.viewer()), ""))
                .contains(mine.documentId()).doesNotContain(other.documentId());
        assertThat(listedIds(user(newEmployee()), "")).isEmpty();
    }

    @Test
    void 목록에서_여러_문서의_결재선에_있으면_모두_보이고_중복되지_않음() throws Exception {
        long sharedApprover = newEmployee();
        long first = createDocument(newEmployee(), line(sharedApprover, 1, "APPROVER"));
        long second = createDocument(newEmployee(), line(sharedApprover, 1, "APPROVER"));

        assertThat(listedIds(user(sharedApprover), "")).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void 관리자도_목록에는_본인이_기안했거나_결재선에_있는_문서만() throws Exception {
        long adminId = newEmployee();
        long drafted = createDocument(adminId, line(newEmployee(), 1, "APPROVER"));
        long assigned = createDocument(newEmployee(), line(adminId, 1, "APPROVER"));
        Fixture other = createFixture();

        assertThat(listedIds(admin(adminId), ""))
                .contains(drafted, assigned).doesNotContain(other.documentId());
        // 목록에서 빠져도 단건 조회 권한은 그대로 (getReadableDocument)
        expectReadable(other.documentId(), admin(adminId));
    }

    @Test
    void 목록의_상태_필터와_권한_필터가_함께_적용() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long approvedDoc = createDocument(drafter, line(approver, 1, "APPROVER"));
        long inProgressDoc = createDocument(drafter, line(approver, 1, "APPROVER"));
        mvc.perform(post(BASE + "/" + approvedDoc + "/approve").with(user(approver)))
                .andExpect(status().isOk());

        assertThat(listedIds(user(drafter), "?approvalStatus=APPROVED")).containsExactly(approvedDoc);
        assertThat(listedIds(user(drafter), "?approvalStatus=INPROGRESS")).containsExactly(inProgressDoc);
        assertThat(listedIds(user(newEmployee()), "?approvalStatus=APPROVED")).isEmpty();
    }

    // ===== 기안함 (box=DRAFTED) =====

    @Test
    void 기안함은_내가_기안한_문서만_결재선에_있는_문서는_제외() throws Exception {
        long me = newEmployee();
        long drafted = createDocument(me, line(newEmployee(), 1, "APPROVER"));
        long assigned = createDocument(newEmployee(), line(me, 1, "APPROVER"));

        assertThat(listedIds(user(me), "")).contains(drafted, assigned);
        assertThat(listedIds(user(me), "?box=DRAFTED")).containsExactly(drafted);
        assertThat(listedIds(user(me), "?box=drafted")).containsExactly(drafted);   // 다른 필터처럼 대소문자 무시
    }

    @Test
    void 관리자도_기안함에는_본인_기안만() throws Exception {
        long adminId = newEmployee();
        long mine = createDocument(adminId, line(newEmployee(), 1, "APPROVER"));
        Fixture other = createFixture();

        assertThat(listedIds(admin(adminId), "?box=DRAFTED"))
                .containsExactly(mine).doesNotContain(other.documentId());
    }

    @Test
    void 기안함과_상태_필터를_함께_적용() throws Exception {
        long drafter = newEmployee();
        long approver = newEmployee();
        long approvedDoc = createDocument(drafter, line(approver, 1, "APPROVER"));
        long inProgressDoc = createDocument(drafter, line(approver, 1, "APPROVER"));
        mvc.perform(post(BASE + "/" + approvedDoc + "/approve").with(user(approver)))
                .andExpect(status().isOk());

        assertThat(listedIds(user(drafter), "?box=DRAFTED&approvalStatus=INPROGRESS")).containsExactly(inProgressDoc);
        assertThat(listedIds(user(approver), "?box=DRAFTED")).isEmpty();
    }

    @Test
    void 잘못된_문서함_값은_400() throws Exception {
        mvc.perform(get(BASE + "?box=INBOX").with(user(newEmployee())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }
}
