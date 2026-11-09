package com.ssafy.approval_service.domain.document;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.BASE;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.line;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 생성 요청 키: 같은 키로 다시 요청하면 문서를 하나만 만들고, 키로 문서를 조회한다 */
class DocumentRequestKeyIntegrationTest extends AbstractIntegrationTest {

    /** 휴가 신청에서 온 결재 생성 요청 */
    private ResultActions submitLeave(long drafter, long approver, long sourceId, String requestKey) throws Exception {
        String keyJson = requestKey == null ? "" : "\"requestKey\":\"%s\",".formatted(requestKey);
        assignTeamHead(drafter, approver);
        return mvc.perform(post(BASE).with(user(drafter)).contentType(MediaType.APPLICATION_JSON).content("""
                {%s"title":"휴가 신청","formType":"LEAVE","sourceType":"LEAVE","sourceId":%d}"""
                .formatted(keyJson, sourceId)));
    }

    private static long documentId(MockHttpServletResponse response) throws Exception {
        Number id = JsonPath.read(response.getContentAsString(), "$.data.documentId");
        return id.longValue();
    }

    @Test
    void 같은_키로_다시_요청하면_처음_만든_문서를_200으로_돌려준다() throws Exception {
        String key = UUID.randomUUID().toString();

        var first = submitLeave(9301L, 9302L, 501L, key).andExpect(status().isCreated()).andReturn().getResponse();
        var second = submitLeave(9301L, 9302L, 501L, key).andExpect(status().isOk()).andReturn().getResponse();

        assertThat(documentId(second)).isEqualTo(documentId(first));
        assertThat((String) JsonPath.read(second.getContentAsString(), "$.data.docNo"))
                .isEqualTo(JsonPath.read(first.getContentAsString(), "$.data.docNo"));
    }

    @Test
    void 같은_키인데_다른_신청이면_409() throws Exception {
        String key = UUID.randomUUID().toString();
        submitLeave(9303L, 9304L, 502L, key).andExpect(status().isCreated());

        submitLeave(9303L, 9304L, 999L, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_021"));
    }

    @Test
    void 출처가_있는데_요청_키가_없으면_400() throws Exception {
        submitLeave(9305L, 9306L, 503L, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_020"));
    }

    @Test
    void 같은_키로_동시에_요청해도_문서는_하나만_생긴다() throws Exception {
        String key = UUID.randomUUID().toString();
        int count = 5;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return submitLeave(9307L, 9308L, 504L, key).andReturn().getResponse();
                }));
            }
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            List<Long> ids = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : futures) {
                MockHttpServletResponse response = f.get();
                statuses.add(response.getStatus());
                ids.add(documentId(response));
            }
            assertThat(statuses).containsOnlyOnce(201);
            assertThat(statuses).filteredOn(s -> s != 201).containsOnly(200);
            assertThat(ids).containsOnly(ids.getFirst());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 요청_키로_문서를_조회한다() throws Exception {
        String key = UUID.randomUUID().toString();
        long id = documentId(submitLeave(9309L, 9310L, 505L, key).andReturn().getResponse());

        // 기안자
        mvc.perform(get(BASE + "/by-request/" + key).with(user(9309L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(id))
                .andExpect(jsonPath("$.data.requestKey").value(key))
                .andExpect(jsonPath("$.data.status").value("INPROGRESS"));
        // 결재선에 없는 사원은 404 (문서 존재 여부를 드러내지 않음)
        mvc.perform(get(BASE + "/by-request/" + key).with(user(9399L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 없는_요청_키는_404() throws Exception {
        mvc.perform(get(BASE + "/by-request/" + UUID.randomUUID()).with(user(9311L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_001"));
    }
}
