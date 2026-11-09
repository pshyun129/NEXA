package com.ssafy.approval_service.global.exception;

import com.ssafy.approval_service.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static com.ssafy.approval_service.support.ApprovalTestFixtures.BASE;
import static com.ssafy.approval_service.support.ApprovalTestFixtures.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 잘못된 query/path 값은 catch-all 500 이 아니라 400 COMMON_001 (요구사항 6장 #16) */
class BadRequestIntegrationTest extends AbstractIntegrationTest {

    private static final long EMP = 500_001L;

    @Test
    void 경로의_문서ID가_숫자가_아니면_400() throws Exception {
        mvc.perform(get(BASE + "/abc").with(user(EMP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        mvc.perform(get(BASE + "/abc/histories").with(user(EMP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        mvc.perform(post(BASE + "/abc/approve").with(user(EMP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void 요청키가_UUID가_아니면_400() throws Exception {
        mvc.perform(get(BASE + "/by-request/not-a-uuid").with(user(EMP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void 결재선_미리보기의_필수_양식이_없거나_틀리면_400() throws Exception {
        mvc.perform(get(BASE + "/approval-line-preview").with(user(EMP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        mvc.perform(get(BASE + "/approval-line-preview").param("formType", "WRONG").with(user(EMP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        mvc.perform(get(BASE + "/approval-line-preview").param("formType", "GENERAL").param("days", "abc")
                        .with(user(EMP)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }
}
