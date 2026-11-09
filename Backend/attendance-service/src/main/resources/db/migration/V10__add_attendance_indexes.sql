-- 사원·근무일로 상태 로그 조회 (출퇴근, 재계산, 이력)
CREATE INDEX idx_work_status_log_emp_work_date ON work_status_log (emp_id, work_date);

-- 사원의 적용 근무그룹 조회 (출근, 재계산 때마다)
CREATE INDEX idx_employee_work_group_emp ON employee_work_group (emp_id);
