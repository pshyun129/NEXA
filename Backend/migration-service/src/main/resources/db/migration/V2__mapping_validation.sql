-- 컬럼 매핑: 원본 컬럼 → 이관 필드 (target_field가 NULL이면 매핑 안 함)
CREATE TABLE column_mapping (
    mapping_id    BIGSERIAL PRIMARY KEY,
    file_id       BIGINT       NOT NULL REFERENCES migration_file (file_id),
    source_column VARCHAR(255) NOT NULL,
    target_field  VARCHAR(100),
    confirmed     BOOLEAN      NOT NULL,
    CONSTRAINT uk_column_mapping_source UNIQUE (file_id, source_column)
);

-- 검증 결과. row_no가 NULL이면 파일 단위 오류 (예: 필수 컬럼 없음)
CREATE TABLE validation_issue (
    issue_id      BIGSERIAL PRIMARY KEY,
    migration_id  BIGINT       NOT NULL,
    file_id       BIGINT       NOT NULL REFERENCES migration_file (file_id),
    row_no        INTEGER,
    field         VARCHAR(100),
    source_column VARCHAR(255),
    severity      VARCHAR(10)  NOT NULL,
    code          VARCHAR(50)  NOT NULL,
    message       TEXT         NOT NULL,
    value         TEXT,
    suggestion    TEXT
);

CREATE INDEX idx_validation_issue_file ON validation_issue (file_id, severity);
CREATE INDEX idx_validation_issue_migration ON validation_issue (migration_id);
