package com.ssafy.nexa.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;

/**
 * 다른 서비스를 호출할 때 현재 요청을 인증한 access 토큰을 Authorization: Bearer 로 그대로 넘긴다.
 * 받는 서비스가 같은 토큰을 다시 검증한다. 인증되지 않은 스레드(스케줄러 등)에서는 헤더를 붙이지 않는다.
 * <pre>{@code
 * RestClient restClient = builder.requestInterceptor(bearerTokenRelayInterceptor).build();
 * }</pre>
 */
public class BearerTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth) {
            request.getHeaders().setBearerAuth(auth.getToken().getTokenValue());
        }
        return execution.execute(request, body);
    }
}
