package com.ssafy.gatewayservice.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TokenRelayFilterTest {

    private final TokenRelayFilter filter = new TokenRelayFilter();

    private static ServerWebExchange exchange(MockServerHttpRequest request, String tokenValue) {
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        if (tokenValue == null) {
            return exchange;
        }
        Jwt jwt = Jwt.withTokenValue(tokenValue).header("alg", "RS256").subject("30").build();
        return exchange.mutate().principal(Mono.just(new JwtAuthenticationToken(jwt))).build();
    }

    private String forwardedAuthorization(ServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = next -> {
            forwarded.set(next);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    }

    @Test
    void 검증된_토큰을_Bearer로_넘김() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/documents")
                .cookie(new org.springframework.http.HttpCookie("accessToken", "cookie-token"))
                .build();

        assertThat(forwardedAuthorization(exchange(request, "cookie-token"))).isEqualTo("Bearer cookie-token");
    }

    @Test
    void 클라이언트가_보낸_Authorization은_검증된_토큰으로_덮어씀() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/documents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer forged-token")
                .build();

        assertThat(forwardedAuthorization(exchange(request, "verified-token"))).isEqualTo("Bearer verified-token");
    }

    @Test
    void 인증되지_않은_요청은_Authorization을_지움() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .header(HttpHeaders.AUTHORIZATION, "Bearer forged-token")
                .build();

        assertThat(forwardedAuthorization(exchange(request, null))).isNull();
    }
}
