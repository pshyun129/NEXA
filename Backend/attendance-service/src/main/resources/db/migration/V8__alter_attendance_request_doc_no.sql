-- 결재 서비스가 채번하는 문서번호({양식}-{연도}-{일련번호 4자리}, 예: OVERTIME-2026-0001)를 저장하도록 타입 변경
-- 결재 서비스 approval_document.doc_no 와 같은 타입
ALTER TABLE attendance_request
ALTER COLUMN doc_no TYPE VARCHAR(255) USING doc_no::VARCHAR;