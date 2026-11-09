ALTER TABLE leave_request
    ADD COLUMN admin_cancel_status VARCHAR(20),
    ADD COLUMN admin_cancel_request_id UUID,
    ADD COLUMN admin_cancel_requested_by BIGINT,
    ADD COLUMN admin_cancel_reason VARCHAR(2000),
    ADD COLUMN admin_cancel_status_changed_at TIMESTAMPTZ,
    ADD CONSTRAINT uq_request_admin_cancel_uuid UNIQUE (admin_cancel_request_id),
    ADD CONSTRAINT ck_request_admin_cancel_metadata CHECK (
        CASE WHEN admin_cancel_status IS NULL THEN
            admin_cancel_request_id IS NULL AND admin_cancel_requested_by IS NULL
            AND admin_cancel_reason IS NULL AND admin_cancel_status_changed_at IS NULL
        ELSE
            admin_cancel_status IN ('IN_PROGRESS', 'UNKNOWN', 'FAILED', 'COMPLETED')
            AND admin_cancel_request_id IS NOT NULL
            AND admin_cancel_requested_by IS NOT NULL AND admin_cancel_requested_by > 0
            AND admin_cancel_reason IS NOT NULL AND admin_cancel_reason !~ '^[[:space:]]*$'
            AND admin_cancel_status_changed_at IS NOT NULL
            AND approval_creation_status IS NOT NULL AND approval_creation_status = 'LINKED'
            AND approval_doc_id IS NOT NULL
            AND (admin_cancel_status <> 'COMPLETED' OR (
                status = 'CANCELLED' AND cancelled_at IS NOT NULL
                AND cancelled_by IS NOT NULL AND cancelled_by = admin_cancel_requested_by
                AND cancel_reason IS NOT NULL AND cancel_reason = admin_cancel_reason
            ))
        END
    );

COMMENT ON COLUMN leave_request.admin_cancel_status IS '관리자 직접 취소의 원격 철회 처리 상태. 원래 결재 생성 상태와 별개';
COMMENT ON COLUMN leave_request.admin_cancel_request_id IS '원신청 기반 결정적 취소 추적 UUID. 재요청 시 유지';
COMMENT ON COLUMN leave_request.admin_cancel_requested_by IS '최초 취소 관리자 직원 ID. 외부 HR FK 없음';
COMMENT ON COLUMN leave_request.admin_cancel_reason IS '최초 취소 사유. 취소 완료 전에도 복구를 위해 보존';
COMMENT ON COLUMN leave_request.admin_cancel_status_changed_at IS '취소 처리 상태 변경 시각';
