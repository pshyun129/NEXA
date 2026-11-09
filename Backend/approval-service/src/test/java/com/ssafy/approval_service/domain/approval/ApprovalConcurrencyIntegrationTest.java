package com.ssafy.approval_service.domain.approval;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.admin;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.line;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.path;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같은 문서에 변경 요청이 동시에 들어와도 한 요청만 반영된다.
 * 경합은 매번 재현되지 않으므로 시나리오마다 새 문서로 여러 번 반복한다
 * (사원 ID 는 200_000번대).
 */
class ApprovalConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicLong EMP_SEQ = new AtomicLong(200_000);
    private static final int ROUNDS = 5;

    private static long newEmployee() {
        return EMP_SEQ.incrementAndGet();
    }

    /** 요청들을 동시에 출발시키고 응답 상태 코드를 요청 순서대로 반환 */
    private List<Integer> concurrently(RequestBuilder... requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.length);
        CountDownLatch ready = new CountDownLatch(requests.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (RequestBuilder request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mvc.perform(request).andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private String read(String path) throws Exception {
        return mvc.perform(get(path).with(admin(newEmployee())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String documentStatus(long documentId) throws Exception {
        return JsonPath.read(read(path(documentId, "")), "$.data.status");
    }

    private List<String> approverActions(long documentId) throws Exception {
        return JsonPath.read(read(path(documentId, "/approvers")), "$.data[*].action");
    }

    private List<Map<String, Object>> approvers(long documentId) throws Exception {
        return JsonPath.read(read(path(documentId, "/approvers")), "$.data");
    }

    private List<String> historyActions(long documentId) throws Exception {
        return JsonPath.read(read(path(documentId, "/histories")), "$.data[*].action");
    }

    @Test
    void 같은_결재자의_동시_승인은_한_번만_반영() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long drafter = newEmployee();
            long first = newEmployee();
            long second = newEmployee();
            long documentId = createDocument(drafter,
                    line(first, 1, "APPROVER"),
                    line(second, 2, "APPROVER"));

            List<Integer> statuses = concurrently(
                    post(path(documentId, "/approve")).with(user(first)),
                    post(path(documentId, "/approve")).with(user(first)));

            // 늦은 요청은 차례가 다음 결재자로 넘어간 뒤라 403 APPROVAL_012
            assertThat(statuses).containsExactlyInAnyOrder(200, 403);
            assertThat(approverActions(documentId)).containsExactly("AGREE", "PENDING");
            assertThat(historyActions(documentId)).containsExactly("SUBMIT", "APPROVE");
        }
    }

    @Test
    void 회수와_승인이_동시에_오면_한쪽만_반영() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long drafter = newEmployee();
            long first = newEmployee();
            long second = newEmployee();
            long documentId = createDocument(drafter,
                    line(first, 1, "APPROVER"),
                    line(second, 2, "APPROVER"));

            List<Integer> statuses = concurrently(
                    post(path(documentId, "/cancel")).with(user(drafter)),
                    post(path(documentId, "/approve")).with(user(first)));

            // 회수가 먼저면 승인은 409 APPROVAL_006, 승인이 먼저면 회수는 409 APPROVAL_008
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            if (documentStatus(documentId).equals("CANCEL")) {
                assertThat(approverActions(documentId)).containsExactly("PENDING", "PENDING");
                assertThat(historyActions(documentId)).containsExactly("SUBMIT", "CANCEL");
            } else {
                assertThat(documentStatus(documentId)).isEqualTo("INPROGRESS");
                assertThat(approverActions(documentId)).containsExactly("AGREE", "PENDING");
                assertThat(historyActions(documentId)).containsExactly("SUBMIT", "APPROVE");
            }
        }
    }

    @Test
    void 같은_step으로_결재자를_동시에_추가하면_하나만_추가() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long drafter = newEmployee();
            long first = newEmployee();
            long documentId = createDocument(drafter, line(first, 1, "APPROVER"));

            List<Integer> statuses = concurrently(
                    post(path(documentId, "/approvers")).with(user(drafter))
                            .contentType(MediaType.APPLICATION_JSON).content(line(newEmployee(), 2, "APPROVER")),
                    post(path(documentId, "/approvers")).with(user(drafter))
                            .contentType(MediaType.APPLICATION_JSON).content(line(newEmployee(), 2, "APPROVER")));

            // 늦은 요청은 step 중복으로 400 APPROVAL_004
            assertThat(statuses).containsExactlyInAnyOrder(201, 400);
            assertThat(approvers(documentId)).extracting(a -> a.get("step")).containsExactly(1, 2);
        }
    }
}
