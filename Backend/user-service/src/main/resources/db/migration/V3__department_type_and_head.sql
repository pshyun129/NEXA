-- 부서 유형과 부서장. approval-service 가 위임전결 규정으로 결재선을 만들 때 쓴다
-- (기안자 부서부터 위로 올라가며 각 단계의 부서장을 모은다)
ALTER TABLE department
    ADD COLUMN dept_type   VARCHAR(20),
    ADD COLUMN head_emp_id BIGINT REFERENCES employee (emp_id);

COMMENT ON COLUMN department.dept_type IS 'DeptType enum (COMPANY/DIVISION/TEAM). 회사/본부/팀';
COMMENT ON COLUMN department.head_emp_id IS '부서장 사원 ID. NULL 이면 공석';
