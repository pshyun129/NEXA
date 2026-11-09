package com.ssafy.leaveservice.client;

import com.ssafy.leaveservice.client.approval.ApprovalCreateCommand;
import com.ssafy.leaveservice.client.approval.ApprovalCreationResult;
import com.ssafy.leaveservice.client.approval.ApprovalHttpClient;
import com.ssafy.leaveservice.client.approval.ApprovalProperties;
import com.ssafy.leaveservice.client.attendance.AttendanceHolidayProperties;
import com.ssafy.leaveservice.client.attendance.HolidayClient;
import com.ssafy.leaveservice.client.attendance.RestHolidayClient;
import com.ssafy.leaveservice.client.hr.HrClient;
import com.ssafy.leaveservice.client.hr.HrIntegrationProperties;
import com.ssafy.leaveservice.client.hr.UserServiceHrClient;
import com.ssafy.leaveservice.domain.request.dto.LeaveSubmissionResult;
import com.ssafy.leaveservice.domain.request.entity.ApprovalCreationStatus;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestStatus;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.BlockingLoadBalancerInterceptor;
import org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "leave.test.database-owner=LeaveHttpClientIntegrationTest",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.loadbalancer.cache.enabled=false",
        "leave.hr.enabled=true",
        "leave.hr.employee-info-uri=/api/v1/employees/{employeeId}",
        "leave.hr.department-members-uri=/api/v1/employees?deptId={departmentId}",
        "leave.hr.duty-info-uri=/api/v1/duties/{dutyId}",
        "spring.jpa.open-in-view=false"
})
@Import(PostgresTestConfiguration.class)
class LeaveHttpClientIntegrationTest {
    @Autowired private LeaveRestClientFactory factory;
    @Autowired private HrClient hrClient;
    @Autowired private HrIntegrationProperties hrProperties;
    @Autowired private ApprovalProperties approvalProperties;
    @Autowired private HolidayClient holidayClient;
    @Autowired private JsonMapper mapper;
    @Autowired private BlockingLoadBalancerInterceptor loadBalancerInterceptor;
    @Autowired @Qualifier("leaveDirectRestClientBuilder") private RestClient.Builder directBuilder;
    @Autowired @Qualifier("leaveLoadBalancedRestClientBuilder") private RestClient.Builder loadBalancedBuilder;

    @MockitoBean(name = "compositeDiscoveryClient")
    private DiscoveryClient discovery;

    private HttpServer first;
    private HttpServer second;
    private ExecutorService executor;
    private List<ServiceInstance> instances;
    private final AtomicInteger firstCalls = new AtomicInteger();
    private final AtomicInteger secondCalls = new AtomicInteger();
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final UUID requestKey = UUID.randomUUID();
    private volatile int responseStatus;
    private volatile long delayMillis;

    enum Target { HR, APPROVAL, ATTENDANCE }

    @BeforeEach
    void setUp() throws IOException {
        executor = Executors.newCachedThreadPool();
        first = startServer(firstCalls);
        second = startServer(secondCalls);
        instances = List.of(instance(first, "first"), instance(second, "second"));
        when(discovery.getInstances(anyString())).thenAnswer(invocation -> instances);
        Jwt token = Jwt.withTokenValue("routing-access").header("alg", "RS256")
                .subject("7").claim("role", "USER").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token, List.of()));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        first.stop(0);
        second.stop(0);
        executor.shutdownNow();
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void explicitUrlBypassesDiscoveryEvenWhenServiceIdExists(Target target) {
        invoke(target, true);

        assertThat(firstCalls).hasValue(1);
        assertThat(secondCalls).hasValue(0);
        assertThat(authorization.get()).isEqualTo("Bearer routing-access");
        verifyNoInteractions(discovery);
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void defaultServiceIdUsesOneAvailableInstance(Target target) {
        instances = List.of(instance(first, "only"));
        invoke(target, false);

        assertThat(firstCalls).hasValue(1);
        assertThat(secondCalls).hasValue(0);
        assertThat(authorization.get()).isEqualTo("Bearer routing-access");
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void springLoadBalancerSelectsMultipleInstances(Target target) {
        for (int index = 0; index < 6; index++) {
            invoke(target, false);
        }

        assertThat(firstCalls).hasValue(3);
        assertThat(secondCalls).hasValue(3);
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void noInstancesKeepsExistingFailureContract(Target target) {
        instances = List.of();
        assertUnavailable(target);
        assertThat(firstCalls).hasValue(0);
        assertThat(secondCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void discoveryFailureDoesNotFallbackToLocalhost(Target target) {
        when(discovery.getInstances(anyString())).thenThrow(new IllegalStateException("Registry unavailable"));
        assertUnavailable(target);
    }

    @Test
    void defaultIdsAndNonRetryingInterceptorAreConfigured() {
        assertThat(hrProperties.serviceId()).isEqualTo("user-service");
        assertThat(approvalProperties.serviceId()).isEqualTo("approval-service");
        assertThat(loadBalancerInterceptor).isInstanceOf(LoadBalancerInterceptor.class);
    }

    @Test
    void clientCreationDoesNotMutateEitherBuilderTemplate() {
        factory.create(url(first), "user-service", Duration.ofSeconds(1), Duration.ofSeconds(2));
        factory.create("", "approval-service", Duration.ofSeconds(2), Duration.ofSeconds(3));
        directBuilder.requestInterceptors(interceptors -> assertThat(interceptors).isEmpty());
        loadBalancedBuilder.requestInterceptors(interceptors -> assertThat(interceptors).hasSize(1));
    }

    @Test
    void directModeIgnoresUnusedServiceId() {
        String response = factory.create(url(first), "unused:8080", Duration.ofSeconds(1), Duration.ofSeconds(2))
                .get().uri("/api/v1/attn/holidays").retrieve().body(String.class);
        assertThat(response).contains("\"success\":true");
        verifyNoInteractions(discovery);
    }

    @Test
    void allUserApiPathsUseTheSameLoadBalancedClient() {
        assertThat(hrClient.findEmployee(7L).employeeId()).isEqualTo(7L);
        assertThat(hrClient.findDuty(9L).name()).isEqualTo("휴가관리자");
        assertThat(hrClient.findDepartmentMembers(3L).members()).hasSize(1);
        assertThat(hrClient.findOrganizationChain(7L).chain().getFirst().headEmpId()).isEqualTo(7L);
        assertThat(firstCalls.get() + secondCalls.get()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 500, 503})
    void approvalPostHttpFailureNeverRetriesOrConfirmsNotCreated(int status) {
        responseStatus = status;
        ApprovalCreationResult result = approval().create(command());

        assertThat(result.outcome()).isEqualTo(ApprovalCreationResult.Outcome.UNKNOWN);
        assertThat(posts).hasValue(1);
    }

    @Test
    void approvalConflictIsNotRetried() {
        responseStatus = 409;
        assertThat(approval().create(command()).outcome()).isEqualTo(ApprovalCreationResult.Outcome.CONFLICT);
        assertThat(posts).hasValue(1);
    }

    @Test
    void approvalLookupRecoversSameUuidThroughLoadBalancer() {
        LeaveSubmissionResult pending = new LeaveSubmissionResult(42L, 7L, requestKey,
                LeaveRequestStatus.SUBMITTING, ApprovalCreationStatus.UNKNOWN, null);
        ApprovalCreationResult recovered = approval().recover(pending);

        assertThat(recovered.outcome()).isEqualTo(ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED);
        assertThat(recovered.documentId()).isEqualTo(101L);
        assertThat(posts).hasValue(0);
    }

    @Test
    void failedInstanceDoesNotCausePostToBeSentToAnotherInstance() throws IOException {
        HttpServer stopped = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int closedPort = stopped.getAddress().getPort();
        stopped.start();
        stopped.stop(0);
        instances = List.of(new DefaultServiceInstance("closed", "approval-service", "127.0.0.1", closedPort, false));

        assertThat(approval().create(command()).outcome()).isEqualTo(ApprovalCreationResult.Outcome.UNKNOWN);
        assertThat(posts).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readTimeoutIsAppliedInBothModesWithoutRetry(boolean direct) {
        delayMillis = 500;
        String baseUrl = direct ? url(first) : "";
        ApprovalHttpClient client = new ApprovalHttpClient(factory.create(
                baseUrl, "approval-service", Duration.ofMillis(200), Duration.ofMillis(100)));

        assertThat(client.create(command()).outcome()).isEqualTo(ApprovalCreationResult.Outcome.UNKNOWN);
        assertThat(posts).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://approval-service", "approval-service:8083", "approval-service/path", "bad id"})
    void invalidServiceIdIsNotReplacedWithLocalhost(String serviceId) {
        assertThatThrownBy(() -> factory.create("", serviceId, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/test", "http://user:password@localhost", "http://localhost?secret=x", "http://localhost#x"})
    void invalidDirectUrlIsRejected(String baseUrl) {
        assertThatThrownBy(() -> factory.create(baseUrl, "user-service", Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void invoke(Target target, boolean direct) {
        String baseUrl = direct ? url(first) : "";
        switch (target) {
            case HR -> {
                HrClient client = direct ? new UserServiceHrClient(factory.create(baseUrl, "user-service",
                        Duration.ofSeconds(1), Duration.ofSeconds(2)), hrProperties) : hrClient;
                assertThat(client.findEmployee(7L).employeeId()).isEqualTo(7L);
            }
            case APPROVAL -> {
                ApprovalHttpClient client = new ApprovalHttpClient(factory.create(baseUrl, "approval-service",
                        Duration.ofSeconds(1), Duration.ofSeconds(2)));
                assertThat(client.create(command()).outcome()).isEqualTo(ApprovalCreationResult.Outcome.SUBMITTED_CONFIRMED);
            }
            case ATTENDANCE -> {
                HolidayClient client = direct ? new RestHolidayClient(new AttendanceHolidayProperties(baseUrl,
                        "attendance-service", Duration.ofSeconds(1), Duration.ofSeconds(2)), factory, mapper) : holidayClient;
                assertThat(client.findHolidays(LocalDate.of(2030, 1, 7), LocalDate.of(2030, 1, 7)).verified()).isTrue();
            }
        }
    }

    private void assertUnavailable(Target target) {
        switch (target) {
            case HR -> assertThatThrownBy(() -> hrClient.findEmployee(7L))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.HR_VERIFICATION_UNAVAILABLE));
            case APPROVAL -> assertThat(approval().create(command()).outcome()).isEqualTo(ApprovalCreationResult.Outcome.UNKNOWN);
            case ATTENDANCE -> assertThat(holidayClient.findHolidays(LocalDate.of(2030, 1, 7), LocalDate.of(2030, 1, 7)).verified()).isFalse();
        }
    }

    private ApprovalHttpClient approval() {
        return new ApprovalHttpClient(factory.create("", "approval-service", Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    private ApprovalCreateCommand command() {
        return new ApprovalCreateCommand(42L, 7L, requestKey, 11L, 1L, "사유", 480, 480,
                List.of(new ApprovalCreateCommand.LeaveDate(1L, OffsetDateTime.parse("2030-01-07T00:00:00+09:00"),
                        LeaveRequestPortion.FULL, 480, 480)));
    }

    private HttpServer startServer(AtomicInteger counter) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, counter));
        server.start();
        return server;
    }

    private void handle(HttpExchange exchange, AtomicInteger counter) throws IOException {
        counter.incrementAndGet();
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        if ("POST".equals(exchange.getRequestMethod())) {
            posts.incrementAndGet();
        }
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            String path = exchange.getRequestURI().getPath();
            String employee = "{\"empId\":7,\"deptId\":3,\"dutyId\":9,\"status\":\"EMPLOYED\"}";
            String data;
            if (path.endsWith("/org-chain")) {
                data = "{\"employeeId\":7,\"deptId\":3,\"chain\":[{\"deptId\":3,\"headEmpId\":7}]}";
            } else if (path.contains("/by-request/")) {
                data = "{\"documentId\":101,\"requestKey\":\"" + requestKey
                        + "\",\"sourceId\":42,\"drafterId\":7,\"sourceType\":\"LEAVE\",\"formType\":\"LEAVE\",\"status\":\"INPROGRESS\"}";
            } else if (path.equals("/api/v1/documents")) {
                data = "{\"documentId\":101}";
            } else if (path.equals("/api/v1/attn/holidays")) {
                data = "[]";
            } else if (path.equals("/api/v1/duties/9")) {
                data = "{\"dutyId\":9,\"name\":\"휴가관리자\"}";
            } else if (path.equals("/api/v1/employees")) {
                data = "{\"employees\":[" + employee + "]}";
            } else {
                data = employee;
            }
            byte[] body = ("{\"success\":true,\"error\":null,\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus == 0 ? 200 : responseStatus, body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private ServiceInstance instance(HttpServer server, String id) {
        return new DefaultServiceInstance(id, "test-service", "127.0.0.1", server.getAddress().getPort(), false);
    }

    private String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
