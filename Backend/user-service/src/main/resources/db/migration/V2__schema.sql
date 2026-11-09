-- user-service 스키마. Hibernate ddl-auto=validate 기준으로 엔티티(domain/user/entity)와 1:1로 맞춘다.
-- V1은 placeholder로 이미 적용된 DB가 있어 수정하지 않고 V2로 추가한다.

CREATE TABLE company (
    company_id  BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    biz_no      BIGINT
);

CREATE TABLE department (
    dept_id        BIGSERIAL PRIMARY KEY,
    name           VARCHAR(255),
    sort_order     INTEGER,
    company_id     BIGINT NOT NULL REFERENCES company (company_id),
    parent_dept_id BIGINT REFERENCES department (dept_id)
);

CREATE TABLE position (
    position_id BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    rank_order  INTEGER
);

CREATE TABLE grade (
    grade_id BIGSERIAL PRIMARY KEY,
    name     VARCHAR(255),
    level    SMALLINT
);

CREATE TABLE duty (
    duty_id BIGSERIAL PRIMARY KEY,
    name    VARCHAR(255)
);

-- 직군(FAMILY) → 직종(KIND) → 직무(ROLE) 계층. type은 JobCodeType enum 문자열
CREATE TABLE job_code (
    job_code_id BIGSERIAL PRIMARY KEY,
    type        VARCHAR(255),
    name        VARCHAR(255),
    parent_id   BIGINT REFERENCES job_code (job_code_id)
);

CREATE TABLE employee (
    emp_id            BIGSERIAL PRIMARY KEY,
    emp_no            VARCHAR(255),
    login_id          VARCHAR(255),
    password_enc      TEXT,
    name              TEXT,
    eng_name          TEXT,
    dept_id           BIGINT NOT NULL REFERENCES department (dept_id),
    position_id       BIGINT NOT NULL REFERENCES position (position_id),
    grade_id          BIGINT NOT NULL REFERENCES grade (grade_id),
    duty_id           BIGINT NOT NULL REFERENCES duty (duty_id),
    emp_type          VARCHAR(255),           -- EmpType enum
    hire_type         VARCHAR(255),           -- HireType enum
    hire_date         DATE,
    recog_hire_date   DATE,
    last_appoint_date DATE,
    job_family_id     BIGINT NOT NULL REFERENCES job_code (job_code_id),
    job_kind_id       BIGINT NOT NULL REFERENCES job_code (job_code_id),
    job_role_id       BIGINT NOT NULL REFERENCES job_code (job_code_id),
    office_phone      VARCHAR(100),
    mobile            VARCHAR(100),
    rrn_enc           VARCHAR(255),
    nationality       VARCHAR(255),
    stay_status       SMALLINT,
    gender            VARCHAR(255),           -- Gender enum
    birth_date        DATE,
    address           TEXT,
    status            VARCHAR(255),           -- EmploymentStatus enum
    resign_date       DATE,
    resign_reason     TEXT,
    leave_policy_id   BIGINT,
    work_group_id     BIGINT,
    role              VARCHAR(20) NOT NULL DEFAULT 'USER',  -- Role enum (USER/ADMIN)
    CONSTRAINT uk_employee_login_id UNIQUE (login_id)
);

CREATE INDEX idx_employee_dept_id ON employee (dept_id);
CREATE INDEX idx_department_parent ON department (parent_dept_id);
