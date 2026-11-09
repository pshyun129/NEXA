-- V6의 기존 Approval 철회 의도와 새 Attendance 원복 의도를 혼동하지 않는다.
ALTER TABLE leave_request
    ADD COLUMN admin_cancel_target VARCHAR(20),
    ADD COLUMN admin_cancel_requested_at TIMESTAMPTZ;

UPDATE leave_request SET admin_cancel_target='APPROVAL',
    admin_cancel_requested_at=admin_cancel_status_changed_at
WHERE admin_cancel_status IS NOT NULL;

ALTER TABLE leave_request ADD CONSTRAINT ck_admin_cancel_target
    CHECK (admin_cancel_target IS NULL OR admin_cancel_target IN ('APPROVAL','ATTENDANCE'));

ALTER TABLE leave_request ADD CONSTRAINT ck_attendance_cancel_requested_at
    CHECK (admin_cancel_target <> 'ATTENDANCE' OR
        (admin_cancel_status IS NOT NULL AND admin_cancel_requested_at IS NOT NULL));

COMMENT ON COLUMN leave_request.admin_cancel_requested_at IS
    '최초 취소 요청 시각. V6 이관 이력은 당시 남아 있는 상태 변경 시각으로 근사';
COMMENT ON COLUMN leave_request.admin_cancel_target IS
    '취소 외부 연동 대상. 기존 APPROVAL 진행 건을 ATTENDANCE 요청으로 재사용하지 않음';
