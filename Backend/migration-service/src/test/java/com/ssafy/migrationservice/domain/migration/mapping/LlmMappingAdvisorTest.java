package com.ssafy.migrationservice.domain.migration.mapping;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchema;
import com.ssafy.migrationservice.domain.migration.validation.schema.EntitySchemas;
import com.ssafy.migrationservice.global.llm.LlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMappingAdvisorTest {

    private static final EntitySchema EMPLOYEE = EntitySchemas.of(MigrationEntity.HR_EMPLOYEE).orElseThrow();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void 값은_모양만_보낸다_숫자_뒤_날짜_단위만_남긴다() {
        assertThat(LlmMappingAdvisor.shape("2015년 3월 2일")).isEqualTo("9999년 9월 9일");
        assertThat(LlmMappingAdvisor.shape("김시원")).isEqualTo("가가가");          // 이름 속 '시·원'은 남기지 않음
        assertThat(LlmMappingAdvisor.shape("kim@nexa.co.kr")).isEqualTo("aaa@aaaa.aa.aa");
        assertThat(LlmMappingAdvisor.shape("E-00123")).isEqualTo("a-99999");
    }

    @Test
    void 응답은_보낸_컬럼_스키마_필드_안_쓴_필드만_받고_한_필드는_한_컬럼에만() {
        LlmMappingAdvisor advisor = new LlmMappingAdvisor(new LlmClient("http://unused", "", "m"), JSON);
        String text = """
                설명 문장이 앞에 붙어도 JSON만 꺼낸다
                {"mappings":[
                  {"column":"사원코드","field":"empNo","reason":"사번"},
                  {"column":"임용일","field":"hireDate","reason":"공공기관 입사일"},
                  {"column":"재입사일","field":"hireDate","reason":"중복"},
                  {"column":"성명","field":"name","reason":"이미 연결됨"},
                  {"column":"비고","field":"memoField","reason":"없는 필드"},
                  {"column":"보내지않은열","field":"email","reason":"보낸 적 없는 컬럼"},
                  {"column":"직렬","field":null,"reason":"없음"}
                ]}""";

        Map<String, LlmMappingAdvisor.Advice> advice = advisor.parse(text, EMPLOYEE,
                Set.of("사원코드", "임용일", "재입사일", "성명", "비고", "직렬"), Set.of("name"));

        assertThat(advice).containsOnlyKeys("사원코드", "임용일");
        assertThat(advice.get("임용일").field()).isEqualTo("hireDate");
    }

    @Test
    void 값_추천은_허용_값의_한글_표기만_받는다() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", ex -> {
            String text = """
                    {"values":[{"value":"Y","target":"재직","reason":"Y는 재직"},
                      {"value":"N","target":"TERMINATED","reason":"코드로 답함"},{"value":"?","target":null,"reason":"모름"}]}""";
            byte[] body = JSON.writeValueAsBytes(Map.of("output", List.of(Map.of("content",
                    List.of(Map.of("type", "output_text", "text", text))))));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var status = EMPLOYEE.field("status").orElseThrow();

            Map<String, LlmMappingAdvisor.Advice> advice = new LlmMappingAdvisor(new LlmClient(base, "k", "m"), JSON)
                    .adviseValues(status, List.of("Y", "N", "?"));

            assertThat(advice).containsOnlyKeys("Y");
            assertThat(advice.get("Y").field()).isEqualTo("재직");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void Responses_API로_묻고_키가_없거나_실패하면_빈_결과() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", ex -> {
            sent.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"status":"completed","output":[{"type":"message","content":[{"type":"output_text",
                     "text":"{\\"mappings\\":[{\\"column\\":\\"사원코드\\",\\"field\\":\\"empNo\\",\\"reason\\":\\"사번\\"}]}"}]}]}
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            Map<String, List<String>> samples = new LinkedHashMap<>();
            samples.put("사원코드", List.of("E-00123", "E-00124"));

            Map<String, LlmMappingAdvisor.Advice> advice = new LlmMappingAdvisor(new LlmClient(base, "test-key", "gpt-test"), JSON)
                    .advise(EMPLOYEE, samples, Set.of());

            assertThat(advice.get("사원코드").field()).isEqualTo("empNo");
            assertThat(sent.get()).contains("gpt-test", "a-99999", "auth=Bearer test-key").doesNotContain("E-00123");
            // 선택지를 답 스키마 enum으로도 보낸다: 보낸 컬럼, 아직 안 쓴 필드
            assertThat(sent.get()).contains("\"json_schema\"", "\"strict\":true", "\"enum\":[\"사원코드\"]", "\"empNo\"");
            assertThat(new LlmMappingAdvisor(new LlmClient(base, "", "m"), JSON).advise(EMPLOYEE, samples, Set.of())).isEmpty();
            assertThat(new LlmMappingAdvisor(new LlmClient("http://127.0.0.1:1/v1", "k", "m"), JSON).advise(EMPLOYEE, samples, Set.of())).isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
