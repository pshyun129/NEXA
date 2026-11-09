package com.ssafy.migrationservice.domain.migration;

import com.ssafy.migrationservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 이관 API는 관리자 토큰만 받는다 (다른 테스트는 기본 요청에 관리자 토큰을 붙여 둠) */
class SecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Test
    void 토큰이_없으면_401_일반_사원이면_403_관리자만_쓸_수_있다() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var create = post("/api/v1/admin/migrations").contentType(MediaType.APPLICATION_JSON).content("""
                {"companyId":1,"name":"권한 테스트"}""");

        mvc.perform(create).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_001"));
        mvc.perform(create.with(jwt().jwt(j -> j.subject("7").claim("type", "access").claim("role", "USER"))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTH_009"));
        mvc.perform(create.with(jwt().jwt(j -> j.subject("1").claim("type", "access").claim("role", "ADMIN"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isCreated());
    }
}
