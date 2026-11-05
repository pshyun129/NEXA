-- migration-service 의 인사 데이터 이관(조직·사원 upsert / batch 되돌리기)을 받기 위한 스키마.
-- 이관 파일은 ID 가 아니라 외부 기준 키(부서코드·사번)로 행을 가리킨다.

-- 1) 외부 부서코드. 기존 부서는 비어 있다 (이관 시 사원 부서는 코드 → 이름 순으로 찾는다)
ALTER TABLE department
    ADD COLUMN dept_code           VARCHAR(50),
    -- 상위 부서·부서장이 아직 없어 연결을 미룬 값. 이후 요청에서 찾으면 연결하고 비운다
    ADD COLUMN pending_parent_code VARCHAR(50),
    ADD COLUMN pending_head_emp_no VARCHAR(50);

CREATE UNIQUE INDEX uk_department_dept_code ON department (dept_code) WHERE dept_code IS NOT NULL;

-- 2) 사번은 이관 upsert 의 기준 키라 중복될 수 없다 (사번 없는 사원은 허용)
CREATE UNIQUE INDEX uk_employee_emp_no ON employee (emp_no) WHERE emp_no IS NOT NULL;

-- 3) batch 별 적재 기록. summary(적재 행 수)와 되돌리기(생성 삭제 / 수정 복원)에 쓴다.
--    같은 batch 에서 같은 행을 여러 번 건드려도 한 줄만 남기고, before_json 은 처음 건드리기 직전 값이다.
--    row_no 가 NULL 인 줄은 원본 행이 아닌 부수 변경(예: 사원 적재 중 부서장 연결)이라 summary 에서 뺀다
CREATE TABLE migration_batch_row (
    id          BIGSERIAL PRIMARY KEY,
    batch_id    VARCHAR(100) NOT NULL,
    entity      VARCHAR(20)  NOT NULL,   -- DEPARTMENT / EMPLOYEE
    target_id   BIGINT       NOT NULL,   -- dept_id / emp_id
    action      VARCHAR(10)  NOT NULL,   -- CREATED / UPDATED
    before_json TEXT,                    -- UPDATED 일 때 수정 전 행 (row_to_json)
    row_no      INTEGER,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_migration_batch_row UNIQUE (batch_id, entity, target_id)
);

CREATE INDEX idx_migration_batch_row_target ON migration_batch_row (entity, target_id);
