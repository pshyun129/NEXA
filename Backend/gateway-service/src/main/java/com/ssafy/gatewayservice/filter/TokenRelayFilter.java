package com.ssafy.gatewayservice.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 가 검증한 Access Token 을 내부 서비스에 Authorization: Bearer 로 넘긴다.
 * 브라우저는 accessToken 쿠키로 보내지만, 내부 서비스는 표준 Bearer 헤더로 받는다.
 */
@Component
public class TokenRelayFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain
    ) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken().getTokenValue())
                .map(token -> withAuthorization(exchange, BEARER_PREFIX + token))
                .defaultIfEmpty(withAuthorization(exchange, null))
                .flatMap(chain::filter);
    }

    // 클라이언트가 보낸 Authorization 헤더는 믿지 않고 항상 덮어쓰거나 지운다
    private ServerWebExchange withAuthorization(
            ServerWebExchange exchange,
            String value
    ) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    if (value == null) {
                        headers.remove(HttpHeaders.AUTHORIZATION);
                    } else {
                        headers.set(HttpHeaders.AUTHORIZATION, value);
                    }
                }))
                .build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
