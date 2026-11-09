package com.ssafy.approval_service.domain.document;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.BASE;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.line;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 문서번호는 서버가 양식·연도별 일련번호로 채번하고, 요청에 docNo 가 있어도 무시한다 */
class DocumentNumberIntegrationTest extends AbstractIntegrationTest {

    private static final int YEAR = Year.now(ZoneId.of("Asia/Seoul")).getValue(); // 문서번호 연도는 한국 시간 기준

    /** 문서를 만들고 서버가 만든 문서번호를 돌려준다 */
    private String create(long drafter, String formType, String extraJson) throws Exception {
        assignTeamHead(drafter, drafter + 1);
        String body = mvc.perform(post(BASE).with(user(drafter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s"title":"번호 테스트","formType":"%s"}"""
                                .formatted(extraJson, formType)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.docNo");
    }

    private static int sequenceOf(String docNo) {
        return Integer.parseInt(docNo.substring(docNo.lastIndexOf('-') + 1));
    }

    @Test
    void 같은_양식은_일련번호가_1씩_이어진다() throws Exception {
        String first = create(9101L, "GENERAL", "");
        String second = create(9103L, "GENERAL", "");

        assertThat(first).matches("GENERAL-" + YEAR + "-\\d{4}");
        assertThat(sequenceOf(second)).isEqualTo(sequenceOf(first) + 1);
    }

    @Test
    void 양식마다_일련번호를_따로_매긴다() throws Exception {
        create(9105L, "GENERAL", "");
        // 출장 신청은 이 테스트에서만 만들므로 1번부터 시작한다
        assertThat(create(9107L, "BUSINESS_TRIP", "")).isEqualTo("BUSINESS_TRIP-" + YEAR + "-0001");
    }

    @Test
    void 요청에_docNo를_보내도_무시한다() throws Exception {
        String docNo = create(9109L, "GENERAL", "\"docNo\":\"MY-NUMBER\",");
        assertThat(docNo).isNotEqualTo("MY-NUMBER").startsWith("GENERAL-" + YEAR + "-");
    }

    @Test
    void 동시에_만들어도_번호가_겹치지_않고_빈칸없이_이어진다() throws Exception {
        int count = 10;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                long drafter = 9200L + i * 2;
                Callable<String> task = () -> {
                    start.await();
                    return create(drafter, "GENERAL", "");
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            List<Integer> sequences = new ArrayList<>();
            for (Future<String> f : futures) {
                sequences.add(sequenceOf(f.get()));
            }
            sequences.sort(Integer::compareTo);
            int min = sequences.getFirst();
            assertThat(sequences).containsExactlyElementsOf(
                    IntStream.range(min, min + count).boxed().toList());
        } finally {
            pool.shutdownNow();
        }
    }
}
