-- 허용 값 목록에 없는 원본 값에 대한 LLM 추천 (재검증 때 다시 묻지 않게 저장). target_value가 NULL이면 추천 없음
CREATE TABLE value_suggestion (
    suggestion_id BIGSERIAL PRIMARY KEY,
    file_id       BIGINT       NOT NULL REFERENCES migration_file (file_id),
    field         VARCHAR(100) NOT NULL,
    source_value  VARCHAR(255) NOT NULL,
    target_value  VARCHAR(100),
    reason        VARCHAR(300),
    CONSTRAINT uk_value_suggestion UNIQUE (file_id, field, source_value)
);
