-- 임시 테이블 값 변경 이력 (자동 정리 일괄 적용, 화면 직접 수정). 감사·되돌리기용
CREATE TABLE staging_change (
    change_id     BIGSERIAL PRIMARY KEY,
    file_id       BIGINT       NOT NULL REFERENCES migration_file (file_id),
    row_no        INTEGER      NOT NULL,
    source_column VARCHAR(255) NOT NULL,
    old_value     TEXT,
    new_value     TEXT,
    change_type   VARCHAR(20)  NOT NULL,   -- AUTO_FIX / MANUAL
    changed_at    TIMESTAMP    NOT NULL
);

CREATE INDEX idx_staging_change_file ON staging_change (file_id, row_no);
CREATE UNIQUE INDEX uk_staging_row_file_row ON staging_row (file_id, row_no);
