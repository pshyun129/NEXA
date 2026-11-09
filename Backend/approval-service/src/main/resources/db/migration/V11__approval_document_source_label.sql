-- 출처 문서의 표시 이름 (예: 업무 자동화 워크플로우 이름). 목록·상세에서 출처를 사람이 읽을 수 있게 보여 준다.
-- 상신 시점의 이름을 저장한다 (원본 이름이 나중에 바뀌어도 문서에는 그때 이름이 남는다). 직접 기안과 기존 문서는 NULL
ALTER TABLE approval_document
    ADD COLUMN source_label VARCHAR(100);

COMMENT ON COLUMN approval_document.source_label IS '출처 표시 이름 (상신 시점 스냅샷, 예: 워크플로우 이름). DIRECT 면 NULL';
