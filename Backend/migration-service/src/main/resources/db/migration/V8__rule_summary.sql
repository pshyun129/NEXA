-- 수식 규칙을 LLM이 사람 말로 정리한 것 (파일당 하나, 다시 만들면 덮어씀)
CREATE TABLE rule_summary (
    file_id    BIGINT PRIMARY KEY REFERENCES migration_file (file_id),
    summary    TEXT         NOT NULL,
    model      VARCHAR(100),
    created_at TIMESTAMP    NOT NULL
);
