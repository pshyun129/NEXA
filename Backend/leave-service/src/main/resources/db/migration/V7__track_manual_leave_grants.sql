-- 지급 결과와 최초 지급 의도를 보존하여 타임아웃 후 재요청에도 같은 결과를 반환한다.
CREATE TABLE leave_manual_grant_receipt (
    request_key VARCHAR(100) PRIMARY KEY,
    payload_hash VARCHAR(64) NOT NULL,
    actor_emp_id BIGINT NOT NULL,
    emp_id BIGINT NOT NULL,
    balance_type_id BIGINT NOT NULL REFERENCES leave_balance_type(balance_type_id),
    grant_mode VARCHAR(20) NOT NULL CHECK (grant_mode IN ('INITIAL', 'ADDITIONAL')),
    grant_id BIGINT NOT NULL UNIQUE REFERENCES leave_grant(grant_id),
    entry_id BIGINT NOT NULL UNIQUE REFERENCES leave_ledger(entry_id),
    remaining_minutes BIGINT NOT NULL CHECK (remaining_minutes >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

-- 관리자와 요청 키가 달라도 직원·잔액 유형별 최초 지급은 한 번만 허용한다.
CREATE UNIQUE INDEX uq_manual_grant_initial
    ON leave_manual_grant_receipt(emp_id, balance_type_id) WHERE grant_mode = 'INITIAL';
