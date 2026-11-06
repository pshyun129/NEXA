package com.ssafy.migrationservice.domain.migration.load;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import com.ssafy.migrationservice.global.exception.BusinessException;
import com.ssafy.migrationservice.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * user-service의 이관용 내부 API 호출 (user-service InternalHrMigrationController).
 *
 * POST   /internal/v1/hr/migration/{references|orgs|employees}/upsert?batchId=  {"rows":[{"rowNo":2,"data":{...}}]}
 *        → ApiResult{data:{created, updated}}. references 는 이미 있는 값을 바꾸지 않는다 (updated = 이미 있던 수)
 * DELETE /internal/v1/hr/migration/batches/{batchId}
 * GET    /internal/v1/hr/migration/batches/{batchId}/summary          → ApiResult{data:{count}}
 * GET    /internal/v1/hr/migration/references                        → 지금 있는 직위·직급·직책·직무 코드
 *
 * 한 번에 CHUNK_SIZE행씩 나눠 보낸다.
 */
@Component
public class HrTargetLoader implements TargetLoader {

    private static final int CHUNK_SIZE = 500;
    private static final Map<MigrationEntity, String> PATHS = Map.of(
            MigrationEntity.HR_CODE, "references",
            MigrationEntity.HR_ORG, "orgs",
            MigrationEntity.HR_EMPLOYEE, "employees");

    private final RestClient restClient;

    public HrTargetLoader(@Value("${migration.targets.hr.base-url}") String baseUrl) {
        // 대상 서비스가 응답하지 않으면 적재 요청이 끝없이 걸리지 않게
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public boolean supports(MigrationEntity entity) {
        return PATHS.containsKey(entity);
    }

    @Override
    public LoadResult upsert(MigrationEntity entity, String batchId, List<LoadRow> rows) {
        LoadResult total = new LoadResult(0, 0);
        for (int from = 0; from < rows.size(); from += CHUNK_SIZE) {
            List<LoadRow> chunk = rows.subList(from, Math.min(from + CHUNK_SIZE, rows.size()));
            InternalResult<UpsertData> res = call(() -> restClient.post()
                    .uri("/internal/v1/hr/migration/{entity}/upsert?batchId={batchId}", PATHS.get(entity), batchId)
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
                .uri("/internal/v1/hr/migration/batches/{batchId}", batchId)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public TargetSummary summarize(MigrationEntity entity, String batchId) {
        InternalResult<SummaryData> res = call(() -> restClient.get()
                .uri("/internal/v1/hr/migration/batches/{batchId}/summary", batchId)
                .retrieve()
                .body(new ParameterizedTypeReference<InternalResult<SummaryData>>() {
                }));
        return new TargetSummary(requireData(res).count());
    }

    public ExistingReferences existingReferences() {
        return requireData(call(() -> restClient.get()
                .uri("/internal/v1/hr/migration/references")
                .retrieve()
                .body(new ParameterizedTypeReference<InternalResult<ExistingReferences>>() {
                })));
    }

    private static <T> T requireData(InternalResult<T> res) {
        if (res == null || !res.success() || res.data() == null) {
            throw new BusinessException(ErrorCode.TARGET_CALL_FAILED, "인사 서비스 응답이 비어 있거나 실패입니다");
        }
        return res.data();
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.TARGET_CALL_FAILED, "인사 서비스 호출 실패: " + e.getMessage());
        }
    }

    // 팀 공통 응답 형식 ApiResult{success, error, data}
    public record InternalResult<T>(boolean success, T data) {
    }

    public record UpsertData(int created, int updated) {
    }

    public record SummaryData(long count) {
    }

    public record Named(String name) {
    }

    public record GradeRef(String name, Integer level) {
    }

    public record JobCodeRef(Long jobCodeId, String type, String name, Long parentId) {
    }

    public record ExistingReferences(List<Named> positions, List<GradeRef> grades, List<Named> duties, List<JobCodeRef> jobCodes) {

        /** 직무 코드 → "직군 > 직종 > 직무" (ReferencePlan 의 값 표기와 같게) */
        public Set<String> jobPaths() {
            Map<Long, JobCodeRef> byId = jobCodes.stream().collect(Collectors.toMap(JobCodeRef::jobCodeId, c -> c));
            Set<String> out = new HashSet<>();
            for (JobCodeRef role : jobCodes) {
                JobCodeRef kind = role.parentId() == null ? null : byId.get(role.parentId());
                JobCodeRef family = kind == null || kind.parentId() == null ? null : byId.get(kind.parentId());
                if ("ROLE".equals(role.type()) && family != null) {
                    out.add(family.name() + " > " + kind.name() + " > " + role.name());
                }
            }
            return out;
        }
    }
}
