ALTER TABLE leave_ledger ADD COLUMN actor_emp_id BIGINT NULL;

COMMENT ON COLUMN leave_ledger.actor_emp_id IS '관리자 수동 조정 처리 직원 ID. 기존 및 시스템 원장은 NULL, 외부 HR FK 없음';
