-- migration-service 스키마. Hibernate ddl-auto=validate 기준으로 엔티티와 맞춘다.
-- staging_row는 JDBC로 직접 다룬다 (엔티티 없음).

CREATE TABLE migration_job (
    migration_id BIGSERIAL PRIMARY KEY,
    company_id   BIGINT       NOT NULL,
    name         VARCHAR(255) NOT NULL,
    cutover_date DATE,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL
);

CREATE TABLE migration_file (
    file_id      BIGSERIAL PRIMARY KEY,
    migration_id BIGINT       NOT NULL REFERENCES migration_job (migration_id),
    entity       VARCHAR(40)  NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    headers      TEXT[]       NOT NULL,
    row_count    INTEGER      NOT NULL,
    uploaded_at  TIMESTAMP    NOT NULL
);

-- 업로드 행 원본. data = { "원본 헤더": "값" }
CREATE TABLE staging_row (
    row_id  BIGSERIAL PRIMARY KEY,
    file_id BIGINT  NOT NULL REFERENCES migration_file (file_id),
    row_no  INTEGER NOT NULL,
    data    JSONB   NOT NULL
);

CREATE INDEX idx_migration_file_migration ON migration_file (migration_id);
CREATE INDEX idx_staging_row_file ON staging_row (file_id, row_no);
