-- 승인된 휴가 (결재 결과 이벤트 수신 후 휴가 서비스에서 날짜를 받아 저장)
CREATE TABLE employee_leave (
                                id           BIGSERIAL PRIMARY KEY,
                                leave_req_id BIGINT      NOT NULL,  -- 휴가 서비스 신청 ID (결재 이벤트 sourceId)
                                emp_id       BIGINT      NOT NULL,
                                leave_date   DATE        NOT NULL,
                                portion      VARCHAR(20) NOT NULL,  -- LeavePortion enum (FULL, AM_HALF, PM_HALF, HOURLY)
                                start_at     TIMESTAMPTZ,           -- 반차·시간차 시작 (종일이면 NULL)
                                end_at       TIMESTAMPTZ,
                                created_at   TIMESTAMPTZ NOT NULL,
                                CONSTRAINT uk_employee_leave_req_date UNIQUE (leave_req_id, leave_date)
);

-- 사원의 그날 휴가 조회 (출근, 재계산 때마다)
CREATE INDEX idx_employee_leave_emp_date ON employee_leave (emp_id, leave_date);
