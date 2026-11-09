-- 결재 문서의 출처(어느 서비스의 어떤 신청에서 만들어졌는지)
-- 기존 문서는 모두 직접 기안(DIRECT)으로 채운다
ALTER TABLE approval_document
    ADD COLUMN source_type VARCHAR(255) NOT NULL DEFAULT 'DIRECT',
    ADD COLUMN source_id   BIGINT;

COMMENT ON COLUMN approval_document.source_type IS 'SourceType enum (DIRECT/LEAVE/ATTENDANCE)';
COMMENT ON COLUMN approval_document.source_id IS '출처 서비스의 원본 신청 ID (DIRECT면 NULL, FK 없음)';

-- 결재 결과를 원본 신청에 돌려줄 때 출처로 문서를 찾는다
CREATE INDEX idx_approval_document_source ON approval_document(source_type, source_id);
