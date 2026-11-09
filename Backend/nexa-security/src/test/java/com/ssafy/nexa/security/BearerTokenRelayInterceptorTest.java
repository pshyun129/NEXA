package com.ssafy.nexa.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.function.Consumer;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BearerTokenRelayInterceptorTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_요청의_토큰을_그대로_넘긴다() {
        Jwt jwt = Jwt.withTokenValue("token-value").header("alg", "RS256").subject("7").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        call(server -> server.expect(requestTo("http://other/api"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-value"))
                .andRespond(withSuccess()));
    }

    @Test
    void 인증_정보가_없으면_헤더를_붙이지_않는다() {
        call(server -> server.expect(requestTo("http://other/api"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess()));
    }

    private static void call(Consumer<MockRestServiceServer> expectation) {
        RestClient.Builder builder = RestClient.builder().requestInterceptor(new BearerTokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectation.accept(server);

        builder.build().get().uri("http://other/api").retrieve().toBodilessEntity();
        server.verify();
    }
}
