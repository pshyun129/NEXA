package com.ssafy.migrationservice.global.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 호출 (OpenAI Responses API 형식, 기본은 SSAFY GMS). 키가 없으면 enabled()=false.
 * 사용자 토큰을 넘기지 않도록 RestClient를 직접 만든다 (자동 등록되는 Bearer 전달 인터셉터를 붙이지 않음).
 */
@Component
public class LlmClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public LlmClient(@Value("${migration.llm.base-url}") String baseUrl, @Value("${migration.llm.api-key:}") String apiKey,
                     @Value("${migration.llm.model}") String model) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    /** 응답 텍스트 (output_text를 이어 붙임). 실패하면 RestClientException 등 런타임 예외 */
    public String call(String instructions, String input) {
        return call(instructions, input, null, null);
    }

    /**
     * 답의 모양을 JSON 스키마로 정해 묻는다 (Structured Outputs, strict). 스키마의 enum에 선택지를 넣으면 그 밖의 값은 나오지 않는다.
     * schema가 null이면 형식 없이 묻는다
     */
    public String call(String instructions, String input, String schemaName, Map<String, Object> schema) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("model", model, "instructions", instructions, "input", input));
        if (schema != null) {
            body.put("text", Map.of("format", Map.of("type", "json_schema", "name", schemaName, "strict", true, "schema", schema)));
        }
        JsonNode res = restClient.post().uri("/responses")
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        StringBuilder text = new StringBuilder();
        for (JsonNode out : res.path("output")) {
            for (JsonNode c : out.path("content")) {
                if ("output_text".equals(c.path("type").asString())) {
                    text.append(c.path("text").asString());
                }
            }
        }
        return text.toString();
    }
}
