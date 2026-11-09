-- V2 이후 추가된 enum 값을 컬럼 주석에 반영 (VARCHAR 컬럼이라 스키마 변경은 없음)
COMMENT ON COLUMN approval_document.status IS 'DocumentStatus enum (INPROGRESS/APPROVED/REJECTED/CANCEL)';
COMMENT ON COLUMN approval_line.role IS 'LineRole enum (APPROVER/AGREEMENT/REFERENCE/VIEWER)';
COMMENT ON COLUMN approval_line.action IS 'LineAction enum (AGREE/DISAGREE/PENDING/SKIPPED)';
COMMENT ON COLUMN approval_history.action IS 'HistoryAction enum (SUBMIT/APPROVE/REJECT/FINAL_APPROVE/CANCEL/RESUBMIT/APPROVER_ADD/APPROVER_REMOVE)';
