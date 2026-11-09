package com.ssafy.leaveservice.client;

import com.ssafy.nexa.security.BearerTokenRelayInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

@Component
public class LeaveRestClientFactory {
    private final RestClient.Builder directBuilder;
    private final RestClient.Builder loadBalancedBuilder;
    private final BearerTokenRelayInterceptor bearerTokenRelay;

    public LeaveRestClientFactory(
            @Qualifier("leaveDirectRestClientBuilder") RestClient.Builder directBuilder,
            @Qualifier("leaveLoadBalancedRestClientBuilder") RestClient.Builder loadBalancedBuilder,
            BearerTokenRelayInterceptor bearerTokenRelay
    ) {
        this.directBuilder = directBuilder;
        this.loadBalancedBuilder = loadBalancedBuilder;
        this.bearerTokenRelay = bearerTokenRelay;
    }

    public RestClient create(
            String baseUrl,
            String serviceId,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        return create(baseUrl, serviceId, connectTimeout, readTimeout, true);
    }

    public RestClient createWithoutBearerRelay(
            String baseUrl,
            String serviceId,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        return create(baseUrl, serviceId, connectTimeout, readTimeout, false);
    }

    private RestClient create(
            String baseUrl,
            String serviceId,
            Duration connectTimeout,
            Duration readTimeout,
            boolean relayBearer
    ) {
        // 명시한 URL은 Discovery를 거치지 않고, URL이 없을 때만 서비스 ID를 사용한다.
        boolean direct = baseUrl != null && !baseUrl.isBlank();
        URI target;
        if (direct) {
            target = URI.create(baseUrl);
            if (target.getScheme() == null || !Set.of("http", "https").contains(target.getScheme()) || target.getHost() == null
                    || target.getRawUserInfo() != null || target.getRawQuery() != null || target.getRawFragment() != null) {
                throw new IllegalArgumentException("HTTP client requires a valid internal base URL");
            }
        } else {
            if (serviceId == null || !serviceId.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) {
                throw new IllegalArgumentException("HTTP client requires a valid service ID without port or path");
            }
            target = URI.create("http://" + serviceId);
        }

        // 연결·읽기 제한과 직원 JWT 전달을 유지하고 Client별 설정 공유를 방지한다.
        HttpClient transport = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(transport);
        requests.setReadTimeout(readTimeout);
        RestClient.Builder builder = direct ? directBuilder.clone() : loadBalancedBuilder.clone();
        if (!direct) {
            builder.requestInterceptors(interceptors -> interceptors.addFirst(
                    LeaveRestClientFactory::executeWithDiscoveryFailureMapping));
        }
        builder.baseUrl(target.toString()).requestFactory(requests);
        if (relayBearer) {
            builder.requestInterceptor(bearerTokenRelay);
        }
        return builder.build();
    }

    private static ClientHttpResponse executeWithDiscoveryFailureMapping(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        try {
            return execution.execute(request, body);
        } catch (IllegalStateException failure) {
            // 인스턴스 미등록도 기존 Client의 외부 통신 실패 계약으로 전달하며 재시도하지 않는다.
            throw new RestClientException("Service discovery is unavailable", failure);
        }
    }
}
