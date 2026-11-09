-- 결재 생성 요청 키. 같은 신청으로 생성 요청이 여러 번 와도 문서는 하나만 만든다 (멱등)
-- leave-service 의 approvalRequestId 처럼 원본 서비스가 신청마다 만든 UUID 를 받는다
ALTER TABLE approval_document
    ADD COLUMN request_key UUID;

ALTER TABLE approval_document
    ADD CONSTRAINT uk_approval_document_request_key UNIQUE (request_key);

COMMENT ON COLUMN approval_document.request_key IS '생성 요청 키 (원본 서비스가 만든 UUID). 출처가 있는 문서는 필수, 직접 기안은 선택';
