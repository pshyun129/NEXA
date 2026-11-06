package com.ssafy.migrationservice.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * user-service 이관용 내부 API의 가짜 구현 (실제 HTTP).
 * user-service InternalHrMigrationController 형식을 그대로 따른다:
 *   POST   /internal/v1/hr/migration/{references|orgs|employees}/upsert?batchId=
 *   DELETE /internal/v1/hr/migration/batches/{batchId}
 *   GET    /internal/v1/hr/migration/batches/{batchId}/summary
 *   GET    /internal/v1/hr/migration/references   (existingGrade 로 넣어 둔 직급만 돌려준다)
 * 기준 키(deptCode, empNo, 기준정보는 종류+값)로 있으면 수정, 없으면 생성. 이름이 "FAIL"인 행을 만나면 그때까지 저장한 뒤 500을 낸다.
 */
public class FakeHrServer {

    private static final Pattern UPSERT = Pattern.compile("/internal/v1/hr/migration/(references|orgs|employees)/upsert");
    private static final Pattern BATCH = Pattern.compile("/internal/v1/hr/migration/batches/([^/]+)(/summary)?");
    private static final Map<String, String> KEYS = Map.of("orgs", "deptCode", "employees", "empNo");

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpServer server;
    // entity → 기준 키 → 저장된 행
    private final Map<String, Map<String, Stored>> store = new ConcurrentHashMap<>();

    public record Stored(String batchId, JsonNode data) {
    }

    public FakeHrServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // 대상에 이미 있는 직급 (이름, 호봉)
    private final List<String> existingGrades = new ArrayList<>();

    public void reset() {
        store.clear();
        existingGrades.clear();
    }

    public void existingGrade(String name, int level) {
        existingGrades.add("{\"name\":\"" + name + "\",\"level\":" + level + "}");
    }

    public List<Stored> rows(String entity) {
        return new ArrayList<>(store.getOrDefault(entity, Map.of()).values());
    }

    private void handle(HttpExchange ex) throws IOException {
        URI uri = ex.getRequestURI();
        Matcher upsert = UPSERT.matcher(uri.getPath());
        Matcher batch = BATCH.matcher(uri.getPath());
        if ("POST".equals(ex.getRequestMethod()) && upsert.matches()) {
            upsert(ex, upsert.group(1), uri.getQuery().replace("batchId=", ""));
        } else if ("DELETE".equals(ex.getRequestMethod()) && batch.matches() && batch.group(2) == null) {
            store.values().forEach(m -> m.values().removeIf(s -> s.batchId().equals(batch.group(1))));
            reply(ex, 200, "{\"success\":true,\"error\":null,\"data\":null}");
        } else if ("GET".equals(ex.getRequestMethod()) && uri.getPath().equals("/internal/v1/hr/migration/references")) {
            reply(ex, 200, "{\"success\":true,\"error\":null,\"data\":{\"positions\":[],\"grades\":["
                    + String.join(",", existingGrades) + "],\"duties\":[],\"jobCodes\":[]}}");
        } else if ("GET".equals(ex.getRequestMethod()) && batch.matches() && batch.group(2) != null) {
            long count = store.values().stream().flatMap(m -> m.values().stream())
                    .filter(s -> s.batchId().equals(batch.group(1))).count();
            reply(ex, 200, "{\"success\":true,\"error\":null,\"data\":{\"count\":" + count + "}}");
        } else {
            reply(ex, 404, "{\"success\":false}");
        }
    }

    private void upsert(HttpExchange ex, String entity, String batchId) throws IOException {
        JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
        Map<String, Stored> table = store.computeIfAbsent(entity, k -> new ConcurrentHashMap<>());
        int created = 0;
        int updated = 0;
        for (JsonNode row : body.get("rows")) {
            JsonNode data = row.get("data");
            if ("FAIL".equals(data.path("name").asString())) {
                reply(ex, 500, "{\"success\":false,\"error\":{\"code\":\"COMMON_999\",\"message\":\"boom\"},\"data\":null}");
                return;
            }
            String key = entity.equals("references") ? data.toString() : data.get(KEYS.get(entity)).asString();
            if (table.put(key, new Stored(batchId, data)) == null) {
                created++;
            } else {
                updated++;
            }
        }
        reply(ex, 200, "{\"success\":true,\"error\":null,\"data\":{\"created\":" + created + ",\"updated\":" + updated + "}}");
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
