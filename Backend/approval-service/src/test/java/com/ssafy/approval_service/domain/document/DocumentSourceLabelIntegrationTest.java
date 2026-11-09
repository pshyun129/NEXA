package com.ssafy.approval_service.domain.document;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.approval_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.BASE;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 출처 표시 이름(sourceLabel): 출처가 있는 문서를 만들 때 함께 받아 상세·목록에 그대로 보여 준다 */
class DocumentSourceLabelIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicLong EMP_SEQ = new AtomicLong(700_000);

    private static long newEmployee() {
        return EMP_SEQ.incrementAndGet();
    }

    /** sourceLabelJson 은 JSON 조각 그대로 ("\"이름\"", "null", "\"  \"" 등). null 이면 필드를 아예 보내지 않는다 */
    private ResultActions create(long drafter, String sourceType, String sourceLabelJson) throws Exception {
        assignTeamHead(drafter, newEmployee());
        String source = sourceType.equals("DIRECT")
                ? "\"sourceType\":\"DIRECT\""
                : "\"sourceType\":\"%s\",\"sourceId\":%d,\"requestKey\":\"%s\""
                        .formatted(sourceType, EMP_SEQ.incrementAndGet(), UUID.randomUUID());
        String label = sourceLabelJson == null ? "" : ",\"sourceLabel\":" + sourceLabelJson;
        return mvc.perform(post(BASE).with(user(drafter)).contentType(MediaType.APPLICATION_JSON).content("""
                {"title":"구매 품의","formType":"GENERAL",%s,"attributes":{"amount":500000}%s}"""
                .formatted(source, label)));
    }

    private long createdId(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.documentId")).longValue();
    }

    @Test
    void 자동화_문서의_출처_이름이_상세와_목록에_나온다() throws Exception {
        long drafter = newEmployee();
        long documentId = createdId(create(drafter, "AUTOMATION", "\"  월말 구매 품의 자동화 \""));

        mvc.perform(get(BASE + "/" + documentId).with(user(drafter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceType").value("AUTOMATION"))
                .andExpect(jsonPath("$.data.sourceLabel").value("월말 구매 품의 자동화"));
        mvc.perform(get(BASE).param("box", "DRAFTED").with(user(drafter)))
                .andExpect(jsonPath("$.data.documents[0].documentId").value(documentId))
                .andExpect(jsonPath("$.data.documents[0].sourceLabel").value("월말 구매 품의 자동화"));
    }

    @Test
    void 보내지_않거나_공백이면_null() throws Exception {
        long drafter = newEmployee();
        long withoutLabel = createdId(create(drafter, "AUTOMATION", null));
        long blankLabel = createdId(create(drafter, "AUTOMATION", "\"   \""));
        long direct = createdId(create(drafter, "DIRECT", null));

        for (long documentId : new long[]{withoutLabel, blankLabel, direct}) {
            mvc.perform(get(BASE + "/" + documentId).with(user(drafter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sourceLabel").isEmpty());
        }
    }

    @Test
    void 직접_기안에_출처_이름을_보내면_400() throws Exception {
        create(newEmployee(), "DIRECT", "\"수동 기안\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void 출처_이름은_100자까지() throws Exception {
        create(newEmployee(), "AUTOMATION", "\"" + "가".repeat(100) + "\"").andExpect(status().isCreated());
        create(newEmployee(), "AUTOMATION", "\"" + "가".repeat(101) + "\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }
}
