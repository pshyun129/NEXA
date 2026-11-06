package com.ssafy.approval_service.domain.document;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** 문서 목록 페이지네이션 (page·size). 파라미터가 없으면 기존처럼 전체 */
class DocumentPaginationIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicLong EMP_SEQ = new AtomicLong(600_000);

    private static long newEmployee() {
        return EMP_SEQ.incrementAndGet();
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<Integer> ids(String body) {
        return JsonPath.read(body, "$.data.documents[*].documentId");
    }

    /** 같은 조건으로 전체를 받은 순서와, 페이지를 이어 붙인 순서가 같은지 */
    private void assertPagesConcatenateToAll(String box, long who, int size, int expectedTotal) throws Exception {
        List<Integer> all = ids(body(get(BASE).param("box", box).with(user(who))));
        assertThat(all).hasSize(expectedTotal);

        List<Integer> joined = new ArrayList<>();
        int pages = (expectedTotal + size - 1) / size;
        for (int page = 0; page < pages; page++) {
            String body = body(get(BASE).param("box", box)
                    .param("page", String.valueOf(page)).param("size", String.valueOf(size)).with(user(who)));
            assertThat((Integer) JsonPath.read(body, "$.data.totalCount")).isEqualTo(expectedTotal);
            assertThat((Integer) JsonPath.read(body, "$.data.page")).isEqualTo(page);
            assertThat((Integer) JsonPath.read(body, "$.data.size")).isEqualTo(size);
            joined.addAll(ids(body));
        }
        assertThat(joined).containsExactlyElementsOf(all);
        // 마지막 페이지 다음은 비어 있고 totalCount 는 그대로
        String after = body(get(BASE).param("box", box)
                .param("page", String.valueOf(pages)).param("size", String.valueOf(size)).with(user(who)));
        assertThat(ids(after)).isEmpty();
        assertThat((Integer) JsonPath.read(after, "$.data.totalCount")).isEqualTo(expectedTotal);
    }

    @Test
    void 파라미터가_없으면_전체를_주고_page_size_필드가_없다() throws Exception {
        long drafter = newEmployee();
        for (int i = 0; i < 3; i++) {
            createDocument(drafter, line(newEmployee(), 1, "APPROVER"));
        }

        String body = body(get(BASE).param("box", "DRAFTED").with(user(drafter)));
        assertThat(ids(body)).hasSize(3);
        assertThat((Integer) JsonPath.read(body, "$.data.totalCount")).isEqualTo(3);
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data).doesNotContainKeys("page", "size");
    }

    @Test
    void 기안함을_페이지로_나눠도_전체_순서와_같다() throws Exception {
        long drafter = newEmployee();
        for (int i = 0; i < 5; i++) {
            createDocument(drafter, line(newEmployee(), 1, "APPROVER"));
        }
        assertPagesConcatenateToAll("DRAFTED", drafter, 2, 5);
    }

    @Test
    void 대기함도_오래된_순서대로_나뉜다() throws Exception {
        long approver = newEmployee();
        for (int i = 0; i < 3; i++) {
            createDocument(newEmployee(), line(approver, 1, "APPROVER"));
        }
        assertPagesConcatenateToAll("PENDING", approver, 2, 3);
    }

    @Test
    void 처리함은_GROUP_BY_쿼리여도_나뉜다() throws Exception {
        long me = newEmployee();
        for (int i = 0; i < 3; i++) {
            // 한 문서에 내 줄이 둘이어도 한 번만 세어야 페이지가 맞는다
            long documentId = createDocument(newEmployee(), line(me, 1, "APPROVER"), line(me, 2, "AGREEMENT"));
            mvc.perform(post(path(documentId, "/approve")).with(user(me))).andExpect(status().isOk());
            mvc.perform(post(path(documentId, "/approve")).with(user(me))).andExpect(status().isOk());
        }
        assertPagesConcatenateToAll("COMPLETED", me, 2, 3);
    }

    @Test
    void size만_주면_0페이지_page만_주면_기본_크기_20() throws Exception {
        long drafter = newEmployee();
        createDocument(drafter, line(newEmployee(), 1, "APPROVER"));

        mvc.perform(get(BASE).param("box", "DRAFTED").param("size", "5").with(user(drafter)))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(5));
        mvc.perform(get(BASE).param("box", "DRAFTED").param("page", "0").with(user(drafter)))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void 범위를_벗어난_page_size는_400() throws Exception {
        long who = newEmployee();
        String[][] invalid = {{"page", "-1"}, {"size", "0"}, {"size", "101"}, {"page", "abc"}};
        for (String[] param : invalid) {
            mvc.perform(get(BASE).param(param[0], param[1]).with(user(who)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
    }
}
