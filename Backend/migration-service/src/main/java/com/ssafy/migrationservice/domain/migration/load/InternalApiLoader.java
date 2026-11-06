package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.global.exception.BusinessException;
import com.ssafy.migrationservice.global.exception.ErrorCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 대상 서비스의 이관용 내부 API 호출. user-service·payroll-service 가 같은 모양으로 연다:
 *
 * POST   {prefix}/{path}/upsert?batchId=  {"rows":[{"rowNo":2,"data":{...}}]} → ApiResult{data:{created, updated}}
 * DELETE {prefix}/batches/{batchId}
 * GET    {prefix}/batches/{batchId}/summary                              → ApiResult{data:{count}}
 *
 * 한 번에 CHUNK_SIZE행씩 나눠 보낸다.
 */
abstract class InternalApiLoader implements TargetLoader {

    private static final int CHUNK_SIZE = 500;

    protected final RestClient restClient;
    private final String prefix;
    private final Map<MigrationEntity, String> paths;
    private final String serviceName;

    protected InternalApiLoader(String baseUrl, String prefix, Map<MigrationEntity, String> paths, String serviceName) {
        // 대상 서비스가 응답하지 않으면 적재 요청이 끝없이 걸리지 않게
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.prefix = prefix;
        this.paths = paths;
        this.serviceName = serviceName;
    }

    @Override
    public boolean supports(MigrationEntity entity) {
        return paths.containsKey(entity);
    }

    @Override
    public LoadResult upsert(MigrationEntity entity, String batchId, List<LoadRow> rows) {
        LoadResult total = new LoadResult(0, 0);
        for (int from = 0; from < rows.size(); from += CHUNK_SIZE) {
            List<LoadRow> chunk = rows.subList(from, Math.min(from + CHUNK_SIZE, rows.size()));
            InternalResult<UpsertData> res = call(() -> restClient.post()
                    .uri(prefix + "/{entity}/upsert?batchId={batchId}", paths.get(entity), batchId)
                    .body(Map.of("rows", chunk))
                    .retrieve()
                    .body(new ParameterizedTypeReference<InternalResult<UpsertData>>() {
                    }));
            UpsertData data = requireData(res);
            total = total.plus(new LoadResult(data.created(), data.updated()));
        }
        return total;
    }

    @Override
    public void rollback(MigrationEntity entity, String batchId) {
        call(() -> restClient.delete()
                .uri(prefix + "/batches/{batchId}", batchId)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public TargetSummary summarize(MigrationEntity entity, String batchId) {
        InternalResult<SummaryData> res = call(() -> restClient.get()
                .uri(prefix + "/batches/{batchId}/summary", batchId)
                .retrieve()
                .body(new ParameterizedTypeReference<InternalResult<SummaryData>>() {
                }));
        return new TargetSummary(requireData(res).count());
    }

    protected <T> T requireData(InternalResult<T> res) {
        if (res == null || !res.success() || res.data() == null) {
            throw new BusinessException(ErrorCode.TARGET_CALL_FAILED, serviceName + " 응답이 비어 있거나 실패입니다");
        }
        return res.data();
    }

    protected <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.TARGET_CALL_FAILED, serviceName + " 호출 실패: " + e.getMessage());
        }
    }

    // 팀 공통 응답 형식 ApiResult{success, error, data}
    public record InternalResult<T>(boolean success, T data) {
    }

    public record UpsertData(int created, int updated) {
    }

    public record SummaryData(long count) {
    }
}
