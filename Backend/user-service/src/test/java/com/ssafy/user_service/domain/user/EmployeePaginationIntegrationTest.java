package com.ssafy.user_service.domain.user;

import com.jayway.jsonpath.JsonPath;
import com.ssafy.user_service.support.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사원 목록 페이지네이션 (page·size). 파라미터가 없으면 기존처럼 전체.
 * 다른 테스트가 사원을 추가하므로 건수는 고정값이 아니라 같은 테스트 안의 전체 조회 결과와 비교한다
 */
class EmployeePaginationIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/employees";

    @Autowired
    MockMvc mvc;

    private Cookie[] user;

    @BeforeEach
    void login() throws Exception {
        user = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user1@local.dev","pass":"admin1234!"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookies();
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(viaGateway(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static List<Integer> ids(String body) {
        return JsonPath.read(body, "$.data.employees[*].empId");
    }

    /** 전체 조회 순서와, 페이지를 이어 붙인 순서가 같은지. deptId 가 null 이면 전체 사원 */
    private void assertPagesConcatenateToAll(Long deptId, int size) throws Exception {
        MockHttpServletRequestBuilder allRequest = get(BASE);
        if (deptId != null) allRequest.param("deptId", deptId.toString());
        String allBody = body(allRequest);
        List<Integer> all = ids(allBody);
        assertThat(all).isSorted();
        assertThat(((Number) JsonPath.read(allBody, "$.data.totalCount")).intValue()).isEqualTo(all.size());

        List<Integer> joined = new ArrayList<>();
        int pages = (all.size() + size - 1) / size;
        for (int page = 0; page < pages; page++) {
            MockHttpServletRequestBuilder request = get(BASE)
                    .param("page", String.valueOf(page)).param("size", String.valueOf(size));
            if (deptId != null) request.param("deptId", deptId.toString());
            String body = body(request);
            assertThat(((Number) JsonPath.read(body, "$.data.totalCount")).intValue()).isEqualTo(all.size());
            assertThat((Integer) JsonPath.read(body, "$.data.page")).isEqualTo(page);
            assertThat((Integer) JsonPath.read(body, "$.data.size")).isEqualTo(size);
            joined.addAll(ids(body));
        }
        assertThat(joined).containsExactlyElementsOf(all);
    }

    @Test
    void 파라미터가_없으면_전체를_사원ID_순으로_주고_page_size_필드가_없다() throws Exception {
        String body = body(get(BASE));
        assertThat(ids(body)).isNotEmpty().isSorted();
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data).containsKey("totalCount").doesNotContainKeys("page", "size");
    }

    @Test
    void 전체_사원을_페이지로_나눠도_전체_순서와_같다() throws Exception {
        assertPagesConcatenateToAll(null, 3);
    }

    @Test
    void 부서원_목록도_나뉜다() throws Exception {
        // 사원이 가장 많은 부서로 확인한다
        String all = body(get(BASE));
        List<Integer> deptIds = JsonPath.read(all, "$.data.employees[*].deptId");
        long deptId = deptIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.groupingBy(id -> id, java.util.stream.Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        assertPagesConcatenateToAll(deptId, 2);
    }

    @Test
    void size만_주면_0페이지_page만_주면_기본_크기_20() throws Exception {
        mvc.perform(get(BASE).param("size", "5").with(viaGateway(user)))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.employees.length()").value(5));
        mvc.perform(get(BASE).param("page", "0").with(viaGateway(user)))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void 범위를_벗어난_page_size와_ids_조합은_400() throws Exception {
        String[][] invalid = {{"page", "-1"}, {"size", "0"}, {"size", "101"}, {"page", "abc"}};
        for (String[] param : invalid) {
            mvc.perform(get(BASE).param(param[0], param[1]).with(viaGateway(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
        mvc.perform(get(BASE).param("ids", "1,2").param("size", "1").with(viaGateway(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    void ids_조회는_그대로이고_totalCount가_붙는다() throws Exception {
        String body = body(get(BASE).param("ids", "101,102"));
        assertThat(ids(body)).containsExactlyInAnyOrder(101, 102);
        assertThat(((Number) JsonPath.read(body, "$.data.totalCount")).intValue()).isEqualTo(2);
    }
}
