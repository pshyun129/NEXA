-- 적재 묶음. 파일 하나를 대상 서비스에 넣은 단위이자 되돌리기 단위
CREATE TABLE migration_batch (
    batch_id       VARCHAR(64) PRIMARY KEY,
    migration_id   BIGINT      NOT NULL REFERENCES migration_job (migration_id),
    file_id        BIGINT      NOT NULL REFERENCES migration_file (file_id),
    entity         VARCHAR(40) NOT NULL,
    status         VARCHAR(20) NOT NULL,   -- LOADING / LOADED / FAILED / ROLLED_BACK
    source_rows    INTEGER     NOT NULL,
    created_count  INTEGER     NOT NULL,
    updated_count  INTEGER     NOT NULL,
    error          TEXT,
    started_at     TIMESTAMP   NOT NULL,
    finished_at    TIMESTAMP
);

CREATE INDEX idx_migration_batch_migration ON migration_batch (migration_id);
