-- 위임전결 규정으로 정한 전결 단계와, 그 판단에 쓴 신청 값(상신 시점 스냅샷)
-- 기존 문서는 결재선을 요청으로 받았으므로 둘 다 NULL 로 둔다
ALTER TABLE approval_document
    ADD COLUMN approval_level VARCHAR(20),
    ADD COLUMN attributes     TEXT;

COMMENT ON COLUMN approval_document.approval_level IS 'ApprovalLevel enum (TEAM/DIVISION/COMPANY). 규정상 전결 단계';
COMMENT ON COLUMN approval_document.attributes IS '전결 판단에 쓴 신청 값 JSON (days, overtimeMinutes, monthlyOvertimeMinutes, overseas, amount). 상신 시점 값';
