package com.ssafy.leaveservice.global.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExceptionTestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void returns400WithFieldValidationMessage() throws Exception {
        mvc.perform(post("/test/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.error.message").value("name: 이름은 필수입니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void returns400ForMalformedJson() throws Exception {
        mvc.perform(post("/test/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void returns409WithoutDatabaseDetails() throws Exception {
        mvc.perform(get("/test/integrity"))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {
                          "success": false,
                          "error": {"code": "COMMON_002", "message": "데이터 제약 조건에 위배됩니다."},
                          "data": null
                        }
                        """));
    }

    @Test
    void returns404ForMissingResource() throws Exception {
        mvc.perform(get("/test/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMON_003"));
    }

    @Test
    void returns500WithoutInternalExceptionDetails() throws Exception {
        mvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                        {
                          "success": false,
                          "error": {"code": "COMMON_999", "message": "서버 오류가 발생했습니다."},
                          "data": null
                        }
                        """));
    }

    @Test
    void preservesBusinessExceptionDetailMessage() throws Exception {
        mvc.perform(get("/test/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.error.message").value("상세 검증 메시지"));
    }

    @RestController
    static class ExceptionTestController {

        @PostMapping("/test/request")
        public void validateRequest(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/test/integrity")
        public void failIntegrity() {
            throw new DataIntegrityViolationException("internal database constraint details");
        }

        @GetMapping("/test/missing-resource")
        public void failNotFound() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "missing-resource", "missing-resource");
        }

        @GetMapping("/test/unexpected")
        public void failUnexpectedly() {
            throw new IllegalStateException("internal exception details");
        }

        @GetMapping("/test/business")
        public void failBusiness() {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "상세 검증 메시지");
        }
    }

    record TestRequest(@NotBlank(message = "이름은 필수입니다.") String name) {
    }
}
