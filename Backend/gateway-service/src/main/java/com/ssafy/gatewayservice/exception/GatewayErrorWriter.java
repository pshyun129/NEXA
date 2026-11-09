package com.ssafy.gatewayservice.exception;


import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
public class GatewayErrorWriter {

    public Mono<Void> write(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message
    ) {
        var response = exchange.getResponse();

        if (response.isCommitted()) {
            return Mono.empty();
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String requestId =
                exchange.getAttributeOrDefault("requestId", "UNKNOWN");

        String json = """
                {
                  "code": "%s",
                  "message": "%s",
                  "requestId": "%s"
                }
                """.formatted(code, message, requestId);

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        return response.writeWith(
                Mono.just(
                        response.bufferFactory().wrap(bytes)
                )
        );
    }
}
