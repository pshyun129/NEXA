package com.ssafy.leaveservice.client.attendance;

import com.sun.net.httpserver.HttpServer;
import com.ssafy.leaveservice.client.LeaveRestClientFactory;
import com.ssafy.nexa.security.BearerTokenRelayInterceptor;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequest;
import org.springframework.web.client.RestClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.DefaultServiceInstance;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestHolidayClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private String body;
    private int status;
    private long delay;
    private final AtomicReference<String> requestUri = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final DiscoveryClient discovery = mock(DiscoveryClient.class);
    private final LocalDate start = LocalDate.of(2030,1,7);
    private final LocalDate end = LocalDate.of(2030,1,10);

    @BeforeEach
    void setUp() throws Exception {
        body = "{\"success\":true,\"error\":null,\"data\":[]}";
        status=200;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if (delay>0) { Thread.sleep(delay); }
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        server.stop(0);
        executor.shutdownNow();
    }

    private RestHolidayClient client(long readMillis) {
        return new RestHolidayClient(new AttendanceHolidayProperties("http://127.0.0.1:"+server.getAddress().getPort(),
                "attendance-service",Duration.ofMillis(500),Duration.ofMillis(readMillis)),clientFactory(),JsonMapper.builder().build());
    }

    @Test
    void emptySuccessAndExactInclusiveRangeQuery() {
        assertThat(client(2000).findHolidays(start,end)).isEqualTo(new HolidayClient.HolidayResult(true,List.of()));
        assertThat(requestUri.get()).isEqualTo("/api/v1/attn/holidays?startDate=2030-01-07&endDate=2030-01-10");
        assertThat(authorization.get()).isNull();
        verifyNoInteractions(discovery);
    }

    @Test
    void discoveryUsesConfiguredRegistrationAndParsesBothHolidayTypes() {
        body="{\"success\":true,\"error\":null,\"data\":[{\"holidayDate\":\"2030-01-07\",\"name\":\"법정\",\"type\":\"LEGAL\"},{\"holidayDate\":\"2030-01-10\",\"name\":\"회사\",\"type\":\"COMPANY\"}]}";
        when(discovery.getInstances("attendance-service")).thenReturn(List.of(new DefaultServiceInstance(
                "test","attendance-service","127.0.0.1",server.getAddress().getPort(),false)));
        RestHolidayClient client = new RestHolidayClient(new AttendanceHolidayProperties("","attendance-service",
                Duration.ofSeconds(1),Duration.ofSeconds(2)),clientFactory(),JsonMapper.builder().build());
        var result=client.findHolidays(start,end);
        assertThat(result.verified()).isTrue();
        assertThat(result.holidays()).extracting(HolidayClient.Holiday::type)
                .containsExactly(HolidayClient.HolidayType.LEGAL,HolidayClient.HolidayType.COMPANY);
    }

    @ParameterizedTest
    @ValueSource(strings={
            "null","[]","bad","{}","{\"success\":false,\"error\":null,\"data\":[]}",
            "{\"success\":true,\"error\":null,\"data\":null}",
            "{\"success\":\"true\",\"error\":null,\"data\":[]}",
            "{\"success\":true,\"error\":null,\"data\":{}}",
            "{\"success\":true,\"error\":null,\"data\":[null]}",
            "{\"success\":true,\"error\":null,\"data\":[{\"holidayDate\":\"2030-01-07\",\"name\":\"x\",\"type\":\"UNKNOWN\"}]}",
            "{\"success\":true,\"error\":null,\"data\":[{\"holidayDate\":\"2030-01-07\",\"name\":\" \",\"type\":\"LEGAL\"}]}",
            "{\"success\":true,\"error\":null,\"data\":[{\"holidayDate\":\"2020-01-07\",\"name\":\"x\",\"type\":\"LEGAL\"}]}",
            "{\"success\":true,\"error\":null,\"data\":[{\"holidayDate\":\"bad\",\"name\":\"x\",\"type\":\"LEGAL\"}]}"
    })
    void malformedContractNeverBecomesEmptyHolidaySuccess(String response) {
        body=response;
        assertThat(client(2000).findHolidays(start,end).verified()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints={400,401,403,500,503,302})
    void non200IsUnverifiable(int httpStatus) {
        status=httpStatus;
        assertThat(client(2000).findHolidays(start,end).verified()).isFalse();
    }

    @Test
    void timeoutDoesNotBecomeEmptySuccess() {
        delay=600;
        assertThat(client(100).findHolidays(start,end).verified()).isFalse();
    }

    @Test
    void connectionFailureDoesNotBecomeEmptySuccess() {
        int port=server.getAddress().getPort();
        server.stop(0);
        assertThat(client(500).findHolidays(start,end).verified()).isFalse();
    }

    @Test
    void noDiscoveredServiceIsUnverifiable() {
        RestHolidayClient client = new RestHolidayClient(new AttendanceHolidayProperties("","missing",
                Duration.ofSeconds(1),Duration.ofSeconds(1)),clientFactory(),JsonMapper.builder().build());
        assertThat(client.findHolidays(start,end).verified()).isFalse();
    }

    @Test
    void discoveryFailureIsUnverifiable() {
        when(discovery.getInstances("attendance-service")).thenThrow(new IllegalStateException("registry unavailable"));
        RestHolidayClient client = new RestHolidayClient(new AttendanceHolidayProperties("","attendance-service",
                Duration.ofSeconds(1),Duration.ofSeconds(1)),clientFactory(),JsonMapper.builder().build());
        assertThat(client.findHolidays(start,end).verified()).isFalse();
    }

    @Test
    void forwardsCurrentEmployeeBearerTokenToAttendance() {
        org.springframework.security.oauth2.jwt.Jwt token = org.springframework.security.oauth2.jwt.Jwt
                .withTokenValue("employee-access-token")
                .header("alg", "RS256")
                .subject("7")
                .claim("role", "USER")
                .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token, List.of()));

        HolidayClient.HolidayResult holidays = client(2000).findHolidays(start, end);

        assertThat(holidays.verified()).isTrue();
        assertThat(authorization.get()).isEqualTo("Bearer employee-access-token");
    }

    // 단위 테스트의 LB 대역이며 실제 Spring 인스턴스 선택은 별도 통합 테스트에서 검증한다.
    private LeaveRestClientFactory clientFactory() {
        LoadBalancerClient balancer = mock(LoadBalancerClient.class);
        when(balancer.reconstructURI(any(), any())).thenAnswer(invocation -> {
            ServiceInstance instance = invocation.getArgument(0);
            URI original = invocation.getArgument(1);
            return org.springframework.web.util.UriComponentsBuilder.fromUri(original)
                    .scheme("http").host(instance.getHost()).port(instance.getPort()).build(true).toUri();
        });
        try {
            when(balancer.execute(anyString(), org.mockito.ArgumentMatchers.<LoadBalancerRequest<Object>>any()))
                    .thenAnswer(invocation -> {
                        String serviceId = invocation.getArgument(0);
                        List<ServiceInstance> instances = discovery.getInstances(serviceId);
                        if (instances.isEmpty()) {
                            throw new IllegalStateException("No test service instances");
                        }
                        LoadBalancerRequest<?> request = invocation.getArgument(1);
                        return request.apply(instances.getFirst());
                    });
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        RestClient.Builder loadBalanced = RestClient.builder()
                .requestInterceptor(new LoadBalancerInterceptor(balancer));
        return new LeaveRestClientFactory(RestClient.builder(), loadBalanced, new BearerTokenRelayInterceptor());
    }
}
