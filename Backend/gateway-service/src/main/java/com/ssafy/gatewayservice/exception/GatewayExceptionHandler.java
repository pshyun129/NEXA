package com.ssafy.gatewayservice.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

@Component
@Order(-2)
public class GatewayExceptionHandler implements WebExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private final GatewayErrorWriter errorWriter;

    public GatewayExceptionHandler(GatewayErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> handle(
            ServerWebExchange exchange,
            Throwable ex
    ) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status;
        String message;

        if (hasCause(ex, TimeoutException.class)) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            message = "서비스 응답 시간이 초과되었습니다.";

        } else if (
                hasCause(ex, ConnectException.class) ||
                        hasCause(ex, UnknownHostException.class)
        ) {
            status = HttpStatus.BAD_GATEWAY;
            message = "대상 서비스에 연결할 수 없습니다.";

        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "Gateway 내부 오류가 발생했습니다.";
        }

        log.error(
                "Gateway error: path={}, status={}",
                exchange.getRequest().getPath(),
                status.value(),
                ex
        );

        return errorWriter.write(
                exchange,
                status,
                status.name(),
                message
        );
    }

    private boolean hasCause(
            Throwable ex,
            Class<? extends Throwable> type
    ) {
        Throwable current = ex;

        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }
}
