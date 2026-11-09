package com.ssafy.leaveservice.domain.request.messaging;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "leave.test.database-owner=ApprovalLeaveFlowIntegrationTest",
        "leave.approval.enabled=true",
        "leave.approval.http-contract-verified=true",
        "leave.approval.results.enabled=true",
        "leave.approval.results.contract-verified=true",
        "spring.cloud.config.enabled=false",
        "spring.cloud.loadbalancer.cache.enabled=false",
        "eureka.client.enabled=false",
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles({"dev", "test"})
@Import(PostgresTestConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalLeaveFlowIntegrationTest {
    private static final String TOPIC = ApprovalResultListener.TOPIC;
    private static final String DLT = TOPIC + ".DLT";
    private static final String REQUESTS = "/api/v1/leaves/me/requests";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicReference<ApprovalLeaveFlowIntegrationTest> SCENARIO = new AtomicReference<>();

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    private static HttpServer externalHttp;
    private static int scenarioNumber;

    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired KafkaTemplate<Object, Object> kafka;

    @MockitoBean(name = "compositeDiscoveryClient")
    DiscoveryClient discovery;

    private final AtomicReference<Throwable> externalFailure = new AtomicReference<>();
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private volatile RemoteDocument remote;
    private volatile boolean loseResponse;
    private volatile int lookupStatus = 200;
    private volatile long lookupSourceOffset;
    private volatile long lookupDrafter = 1;
    private volatile long lookupDocumentOffset;
    private MockMvc mvc;
    private LocalDate day;

    @BeforeAll
    static void createTopics() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(DLT, 1, (short) 1)))
                    .all().get(20, TimeUnit.SECONDS);
        }
    }

    @DynamicPropertySource
    static void configureEndpoints(DynamicPropertyRegistry registry) throws IOException {
        externalHttp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        externalHttp.createContext("/api/v1/documents", exchange -> SCENARIO.get().handleApproval(exchange));
        externalHttp.createContext("/api/v1/attn/holidays", exchange -> {
            ApprovalLeaveFlowIntegrationTest scenario = SCENARIO.get();
            scenario.holidayCalls.incrementAndGet();
            scenario.holidayAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            int responseStatus = "Bearer flow-test-access".equals(scenario.holidayAuthorization.get()) ? 200 : 401;
            respond(exchange, responseStatus, "{\"success\":true,\"error\":null,\"data\":[]}");
        });
        externalHttp.start();
        registry.add("leave.approval.base-url", () -> "");
        registry.add("leave.attendance.base-url", () -> "");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @AfterAll
    static void stopExternalHttp() {
        if (externalHttp != null) {
            externalHttp.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        SCENARIO.set(this);
        org.mockito.Mockito.when(discovery.getInstances(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> List.of(new DefaultServiceInstance("flow", invocation.getArgument(0),
                        "127.0.0.1", externalHttp.getAddress().getPort(), false)));
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        Jwt token = Jwt.withTokenValue("flow-test-access").header("alg", "RS256")
                .subject("1").claim("type", "access").claim("role", "USER").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token, List.of()));

        day = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusWeeks(4 + scenarioNumber++)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        jdbc.update("insert into leave_policy_group(policy_id,name) values(1,'정책') on conflict do nothing");
        jdbc.update("""
                insert into leave_policy_version(policy_version_id,policy_id,version_no,base_type,unit_minutes)
                values(11,1,1,'HIRE_DATE',30) on conflict do nothing
                """);
        jdbc.update("update leave_policy_group set active_policy_version_id=11 where policy_id=1");
        jdbc.update("""
                insert into employee_leave_policy_assignment(assignment_id,emp_id,policy_id,effective_from)
                values(1,1,1,'2000-01-01Z') on conflict do nothing
                """);
        jdbc.update("insert into leave_balance_type(balance_type_id,name) values(1,'연차') on conflict do nothing");
        jdbc.update("""
                insert into leave_type(leave_type_id,balance_type_id,name,balance_mode,request_unit)
                values(1,1,'연차','ACCOUNT','MIXED') on conflict do nothing
                """);
        jdbc.update("""
                insert into leave_policy_type_rule(rule_id,policy_version_id,leave_type_id,allowed_units)
                values(1,11,1,'MIXED') on conflict do nothing
                """);
        jdbc.update("insert into leave_account(account_id,emp_id,balance_type_id) values(1,1,1) on conflict do nothing");
        jdbc.update("""
                insert into leave_grant(grant_id,account_id,grant_kind,original_minutes,valid_from)
                values(100,1,'MANUAL',10080,'2000-01-01Z') on conflict do nothing
                """);
        jdbc.update("""
                insert into leave_ledger(account_id,grant_id,event_type,quantity_delta,idempotency_key,created_at)
                values(1,100,'GRANT',10080,'flow-grant','2000-01-01Z') on conflict do nothing
                """);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
        SCENARIO.set(null);
        assertThat(externalFailure.get()).isNull();
    }

    private final AtomicInteger holidayCalls = new AtomicInteger();
    private final AtomicReference<String> holidayAuthorization = new AtomicReference<>();

    @Test
    void previewAndSubmissionRelayEmployeeTokenToActualHolidayPath() throws Exception {
        String body = """
                {"leaveTypeId":1,"dates":[{"leaveDate":"%s","portion":"FULL"}]}
                """.formatted(day);
        mvc.perform(post(REQUESTS + "/preview").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(holidayCalls).hasValue(1);
        assertThat(holidayAuthorization.get()).isEqualTo("Bearer flow-test-access");

        submit().andExpect(status().isCreated());
        assertThat(holidayCalls).hasValue(2);
        assertThat(holidayAuthorization.get()).isEqualTo("Bearer flow-test-access");

        // 공유 테스트 계정에 예약을 남기지 않도록 기존 반려 정산까지 완료한다.
        publish(event("REJECTED"));
        String conflictingApproval = event("APPROVED");
        publish(conflictingApproval);
        awaitDeadLetter(conflictingApproval);
        assertSettlement("REJECTED", "RELEASE", 0);
    }

    @ParameterizedTest
    @CsvSource({"APPROVED,APPROVED,CONSUME,-480,REJECTED", "REJECTED,REJECTED,RELEASE,0,APPROVED",
            "CANCEL,WITHDRAWN,RELEASE,0,APPROVED"})
    void settlesThroughControllerHttpKafkaAndDatabase(
            String result, String finalState, String eventType, int quantityDelta, String conflictingResult
    ) throws Exception {
        long before = quantity();
        submit().andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.approvalCreationStatus").value("LINKED"));
        assertThat(row()).containsEntry("approval_doc_id", remote.documentId());

        String event = event(result);
        publish(event);
        publish(event);
        String conflict = event(conflictingResult);
        publish(conflict);
        // 같은 파티션에서 후속 충돌이 DLT에 도착하면 앞선 두 레코드도 소비된 상태다.
        awaitDeadLetter(conflict);

        assertSettlement(finalState, eventType, quantityDelta);
        assertThat(quantity()).isEqualTo(before + quantityDelta);
        mvc.perform(get("/api/v1/leaves/me/balances")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].quantityMinutes").value(before + quantityDelta))
                .andExpect(jsonPath("$.data[0].reservedMinutes").value(0));
        if ("CANCEL".equals(result)) {
            assertThat(row()).containsEntry("cancelled_by", 1L)
                    .containsEntry("cancel_reason", "기안자 결재 문서 회수");
            assertThat(row().get("cancelled_at")).isNotNull();
        }
        assertThat(posts).hasValue(1);
    }

    @Test
    void recoversLostResponseAndReplaysEarlyApprovalFromDlt() throws Exception {
        loseResponse = true;
        submit().andExpect(status().isAccepted()).andExpect(jsonPath("$.data.approvalCreationStatus").value("UNKNOWN"));
        assertThat(row()).containsEntry("approval_doc_id", null);

        String earlyApproval = event("APPROVED");
        publish(earlyApproval);
        awaitDeadLetter(earlyApproval);
        assertThat(row()).containsEntry("status", "SUBMITTING");
        assertThat(reserved()).isEqualTo(480L);

        lookupStatus = 404;
        submit().andExpect(status().isAccepted());
        assertThat(reserved()).isEqualTo(480L);
        lookupStatus = 200;
        lookupSourceOffset = 1;
        submit().andExpect(status().isConflict());
        lookupSourceOffset = 0;
        lookupDrafter = 2;
        submit().andExpect(status().isConflict());
        lookupDrafter = 1;
        lookupDocumentOffset = -remote.documentId();
        submit().andExpect(status().isConflict());
        assertThat(row()).containsEntry("approval_doc_id", null);
        assertThat(reserved()).isEqualTo(480L);

        lookupDocumentOffset = 0;
        submit().andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        assertThat(reserved()).isEqualTo(480L);
        submit().andExpect(status().isCreated());
        assertThat(posts).hasValue(1);
        assertThat(lookups).hasValue(5);

        publish(earlyApproval);
        String conflict = event("REJECTED");
        publish(conflict);
        awaitDeadLetter(conflict);
        assertSettlement("APPROVED", "CONSUME", -480);
    }

    @Test
    void rollsBackDatabaseFailureAndCanReplayOriginalApproval() throws Exception {
        submit().andExpect(status().isCreated());
        String approval = event("APPROVED");
        long before = quantity();
        jdbc.execute("""
                create function fail_flow_consume() returns trigger language plpgsql as $$
                begin
                    if new.event_type='CONSUME' then
                        raise exception 'test settlement write failure';
                    end if;
                    return new;
                end $$
                """);
        jdbc.execute("""
                create trigger fail_flow_consume before insert on leave_ledger
                for each row execute function fail_flow_consume()
                """);
        try {
            publish(approval);
            awaitDeadLetter(approval);
            assertThat(row()).containsEntry("status", "SUBMITTED");
            assertThat(reserved()).isEqualTo(480L);
            assertThat(quantity()).isEqualTo(before);
        } finally {
            jdbc.execute("drop trigger fail_flow_consume on leave_ledger");
            jdbc.execute("drop function fail_flow_consume()");
        }

        publish(approval);
        String conflict = event("REJECTED");
        publish(conflict);
        awaitDeadLetter(conflict);
        assertSettlement("APPROVED", "CONSUME", -480);
        assertThat(quantity()).isEqualTo(before - 480);
    }

    private ResultActions submit() throws Exception {
        return mvc.perform(post(REQUESTS).header("Idempotency-Key", "flow-" + day)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"leaveTypeId":1,"dates":[{"leaveDate":"%s","portion":"FULL"}]}
                        """.formatted(day)));
    }

    private void handleApproval(HttpExchange exchange) {
        try (exchange) {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer flow-test-access");
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.incrementAndGet();
                JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                long requestId = body.path("sourceId").asLong();
                remote = new RemoteDocument(requestId, requestId + 1000, body.path("requestKey").asString());
                assertThat(body.path("sourceType").asString()).isEqualTo("LEAVE");
                assertThat(body.path("formType").asString()).isEqualTo("LEAVE");
                assertThat(body.path("attributes").path("days").asInt()).isEqualTo(1);
                assertThat(body.has("approvalLines")).isFalse();
                assertThat(row()).containsEntry("approval_request_id", UUID.fromString(remote.requestKey()))
                        .containsEntry("approval_creation_status", "IN_PROGRESS");
                assertThat(reserved()).isEqualTo(480L);
                if (!loseResponse) {
                    respond(exchange, 201, "{\"success\":true,\"data\":{\"documentId\":" + remote.documentId() + "}}");
                }
                return;
            }

            lookups.incrementAndGet();
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/v1/documents/by-request/" + remote.requestKey());
            respond(exchange, lookupStatus, """
                    {"success":true,"data":{"documentId":%d,"requestKey":"%s","sourceId":%d,
                     "sourceType":"LEAVE","formType":"LEAVE","drafterId":%d,"status":"APPROVED"}}
                    """.formatted(remote.documentId() + lookupDocumentOffset, remote.requestKey(),
                    remote.requestId() + lookupSourceOffset, lookupDrafter));
        } catch (Throwable failure) {
            externalFailure.compareAndSet(null, failure);
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String event(String status) {
        return """
                {"eventId":"%s","eventVersion":1,"eventType":"DOCUMENT_COMPLETED","occurredAt":"2026-11-03T01:02:03Z",
                 "documentId":%d,"docNo":"LEAVE-TEST","sourceType":"LEAVE","formType":"LEAVE",
                 "sourceId":%d,"requestKey":"%s","status":"%s"}
                """.formatted(UUID.randomUUID(), remote.documentId(), remote.requestId(), remote.requestKey(), status);
    }

    private void publish(String event) throws Exception {
        kafka.send(TOPIC, String.valueOf(remote.documentId()), event).get(10, TimeUnit.SECONDS);
    }

    private void awaitDeadLetter(String expected) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of("bootstrap.servers", KAFKA.getBootstrapServers()), new StringDeserializer(), new StringDeserializer()
        )) {
            TopicPartition partition = new TopicPartition(DLT, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (expected.equals(record.value())) {
                        assertThat(record.key()).isEqualTo(String.valueOf(remote.documentId()));
                        return;
                    }
                }
            }
            throw new AssertionError("Expected original Kafka payload in DLT");
        }
    }

    private void assertSettlement(String finalState, String eventType, int quantityDelta) {
        assertThat(row()).containsEntry("status", finalState).containsEntry("approval_doc_id", remote.documentId());
        assertThat(jdbc.queryForList("""
                select l.event_type, l.quantity_delta, l.reserved_delta
                from leave_ledger l join leave_request_allocation a
                  on (a.allocation_id,a.leave_req_id,a.account_id,a.grant_id)
                     = (l.allocation_id,l.leave_req_id,l.account_id,l.grant_id)
                where l.leave_req_id=? order by l.entry_id
                """, remote.requestId())).containsExactly(
                Map.of("event_type", "RESERVE", "quantity_delta", 0, "reserved_delta", 480),
                Map.of("event_type", eventType, "quantity_delta", quantityDelta, "reserved_delta", -480)
        );
        assertThat(reserved()).isZero();
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("select * from leave_request where leave_req_id=?", remote.requestId());
    }

    private long reserved() {
        return jdbc.queryForObject(
                "select coalesce(sum(reserved_delta),0) from leave_ledger where leave_req_id=?", Long.class, remote.requestId()
        );
    }

    private long quantity() {
        return jdbc.queryForObject("select sum(quantity_delta) from leave_ledger where account_id=1", Long.class);
    }

    private record RemoteDocument(long requestId, long documentId, String requestKey) {}
}
