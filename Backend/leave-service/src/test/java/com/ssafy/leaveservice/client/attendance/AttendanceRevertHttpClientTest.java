package com.ssafy.leaveservice.client.attendance;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceRevertHttpClientTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private AttendanceRevertHttpClient client;
    private AttendanceRevertCommand command;
    private String response;
    private int status;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile String requestBody;
    private volatile String path;

    @BeforeEach
    void start() throws Exception {
        command = new AttendanceRevertCommand(100L, 42L, 9001L, UUID.randomUUID(), 10L, "취소");
        status = 200;
        response = successBody(true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new AttendanceRevertHttpClient(RestClient.builder().baseUrl(url()).requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient())).build(), true);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sendsOnlyCancellationUuidAndAcceptsFinalSuccess(boolean reverted) {
        response = successBody(reverted);
        AttendanceRevertResult result = client.revert(command);
        assertThat(result.outcome()).isEqualTo(AttendanceRevertResult.Outcome.REVERTED_CONFIRMED);
        assertThat(result.requestId()).isEqualTo(100L);
        assertThat(result.cancelRequestId()).isEqualTo(command.cancelRequestId());
        assertThat(path).isEqualTo("POST /api/v1/attn/internal/leave-requests/100/revert");
        assertThat(requestBody).isEqualTo("{\"cancelRequestId\":\"" + command.cancelRequestId() + "\"}");
    }

    @ParameterizedTest
    @ValueSource(ints = {202, 400, 401, 403, 404, 409, 500, 503})
    void httpStatusCannotProveRevertOrFinalRejection(int status) {
        this.status = status;
        assertThat(client.revert(command).outcome()).isEqualTo(AttendanceRevertResult.Outcome.UNKNOWN);
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "INVALID_JSON", "ENVELOPE", "WRONG_ID", "WRONG_UUID", "MISSING_UUID",
            "MISSING_BOOLEAN", "NULL_BOOLEAN", "STRING_BOOLEAN", "NUMBER_BOOLEAN", "MISSING_DATES",
            "BAD_DATE", "FRACTIONAL_ID", "STRING_ID"})
    void malformedOrUncorrelatedResponseRemainsUnknown(String scenario) {
        response = switch (scenario) {
            case "EMPTY" -> "{}";
            case "INVALID_JSON" -> "not json";
            case "ENVELOPE" -> "{\"success\":true,\"data\":" + response + "}";
            case "WRONG_ID" -> response.replace("100", "101");
            case "WRONG_UUID" -> response.replace(command.cancelRequestId().toString(), UUID.randomUUID().toString());
            case "MISSING_UUID" -> response.replace("\"cancelRequestId\":\"" + command.cancelRequestId() + "\",", "");
            case "MISSING_BOOLEAN" -> response.replace("\"reverted\":true,", "");
            case "NULL_BOOLEAN" -> response.replace("\"reverted\":true", "\"reverted\":null");
            case "STRING_BOOLEAN" -> response.replace("\"reverted\":true", "\"reverted\":\"true\"");
            case "NUMBER_BOOLEAN" -> response.replace("\"reverted\":true", "\"reverted\":1");
            case "MISSING_DATES" -> response.replace(",\"revertedDates\":[\"2026-11-13\"]", "");
            case "BAD_DATE" -> response.replace("2026-11-13", "not-a-date");
            case "FRACTIONAL_ID" -> response.replace("\"leaveRequestId\":100", "\"leaveRequestId\":100.5");
            case "STRING_ID" -> response.replace("\"leaveRequestId\":100", "\"leaveRequestId\":\"100\"");
            default -> throw new IllegalArgumentException(scenario);
        };
        assertThat(client.revert(command).outcome()).isEqualTo(AttendanceRevertResult.Outcome.UNKNOWN);
    }

    private String successBody(boolean reverted) {
        return "{\"leaveRequestId\":100,\"cancelRequestId\":\"" + command.cancelRequestId()
                + "\",\"reverted\":" + reverted + ",\"revertedDates\":" + (reverted ? "[\"2026-11-13\"]" : "[]") + "}";
    }

    @Test
    void connectionFailureCannotBeConfirmedAsRevert() throws Exception {
        int port;
        try (java.net.ServerSocket unused = new java.net.ServerSocket(0)) { port = unused.getLocalPort(); }
        AttendanceRevertHttpClient disconnected = new AttendanceRevertHttpClient(RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()))
                .build(), true);
        assertThat(disconnected.revert(command).outcome()).isEqualTo(AttendanceRevertResult.Outcome.UNKNOWN);
    }

    @Test
    void recoveryResendsSameUuidOnlyAfterIdempotencyContractIsVerified() {
        AttendanceRevertHttpClient unverified = new AttendanceRevertHttpClient(RestClient.builder().baseUrl(url()).requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient())).build(), false);
        assertThat(unverified.recover(command).outcome()).isEqualTo(AttendanceRevertResult.Outcome.UNKNOWN);
        assertThat(calls).hasValue(0);
        client.revert(command);
        String originalBody = requestBody;
        client.recover(command);
        assertThat(calls).hasValue(2);
        assertThat(requestBody).isEqualTo(originalBody);
    }

    @Test
    void responseTimeoutKeepsOutcomeUnknownWithoutAutomaticRetry() {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        JdkClientHttpRequestFactory transport = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        transport.setReadTimeout(Duration.ofMillis(100));
        client = new AttendanceRevertHttpClient(RestClient.builder().baseUrl(url()).requestFactory(transport).build(), true);
        assertThat(client.revert(command).outcome()).isEqualTo(AttendanceRevertResult.Outcome.UNKNOWN);
        assertThat(calls).hasValue(1);
    }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
}
