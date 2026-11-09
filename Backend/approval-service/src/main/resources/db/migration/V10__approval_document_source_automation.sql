-- 업무 자동화(ai-service) 워크플로우가 상신한 문서를 출처 AUTOMATION 으로 구분한다.
-- source_type 은 VARCHAR 이고 CHECK 제약이 없어 스키마는 그대로, 설명만 갱신한다
COMMENT ON COLUMN approval_document.source_type IS 'SourceType enum (DIRECT/LEAVE/ATTENDANCE/AUTOMATION)';
COMMENT ON COLUMN approval_document.source_id IS '출처 서비스의 원본 신청 ID (AUTOMATION 이면 워크플로우 실행 ID, DIRECT면 NULL, FK 없음)';
