
package com.ssafy.gatewayservice.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter implements WebFilter {

    private static final Logger log =
            LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            WebFilterChain chain
    ) {
        String requestId = UUID.randomUUID().toString();
        long start = System.nanoTime();

        exchange.getAttributes().put("requestId", requestId);

        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    // 클라이언트의 내부 인증 헤더 위조 방지
                    headers.remove("X-User-Id");
                    headers.remove("X-User-Role");

                    headers.set("X-Request-Id", requestId);
                })
                .build();

        exchange.getResponse()
                .getHeaders()
                .set("X-Request-Id", requestId);

        exchange.getResponse().beforeCommit(() -> {

            long elapsedMs =
                    (System.nanoTime() - start) / 1_000_000;

            var status = exchange.getResponse().getStatusCode();

            log.info(
                    "requestId={} method={} path={} status={} durationMs={}",
                    requestId,
                    request.getMethod(),
                    request.getPath().value(),
                    status == null ? "UNKNOWN" : status.value(),
                    elapsedMs
            );

            return Mono.empty();
        });

        return chain.filter(
                exchange.mutate().request(request).build()
        );
    }
}
