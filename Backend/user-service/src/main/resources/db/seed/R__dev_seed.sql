-- 로컬/개발 전용 초기 데이터. dev 프로필에서만 flyway.locations에 포함된다.
-- Repeatable(R__) 마이그레이션이라 버전 순서와 무관하게 모든 V__ 뒤에 실행되며, 재실행 가능해야 한다.
-- 최초 관리자: admin@local.dev / admin1234!  (운영에는 절대 포함하지 말 것)
--
-- 기준 데이터(회사·부서·직위·직급·직책·직무)는 이 파일이 내용까지 관리한다 (ON CONFLICT DO UPDATE).
-- 이 파일이 바뀌면 같은 ID 의 행은 여기 값으로 덮어쓴다. 로컬에서 손으로 고친 값은 되돌아간다.

-- ===== 회사 =====
INSERT INTO company (company_id, name, biz_no)
VALUES (1, 'DEV COMPANY', 1234567890)
ON CONFLICT (company_id) DO UPDATE SET name = EXCLUDED.name, biz_no = EXCLUDED.biz_no;

-- ===== 부서 (회사 > 본부 > 팀) =====
-- dept_type: COMPANY(회사, 부서장=대표이사) / DIVISION(본부, 본부장) / TEAM(팀, 팀장). 결재 전결 단계 기준
-- 기존 ID 1~3(인사팀·개발팀·영업팀)은 유지하고 본부 아래로 옮긴다. 상위 부서를 먼저 넣는다
-- 부서장(head_emp_id)은 사원을 넣은 뒤 맨 아래에서 지정한다
INSERT INTO department (dept_id, name, sort_order, company_id, parent_dept_id, dept_type) VALUES
    (13, 'DEV COMPANY', 1, 1, NULL, 'COMPANY')
ON CONFLICT (dept_id) DO UPDATE SET
    name = EXCLUDED.name, sort_order = EXCLUDED.sort_order, company_id = EXCLUDED.company_id,
    parent_dept_id = EXCLUDED.parent_dept_id, dept_type = EXCLUDED.dept_type;

INSERT INTO department (dept_id, name, sort_order, company_id, parent_dept_id, dept_type) VALUES
    (10, '경영지원본부', 1, 1, 13, 'DIVISION'),
    (11, '기술본부',     2, 1, 13, 'DIVISION'),
    (12, '영업본부',     3, 1, 13, 'DIVISION')
ON CONFLICT (dept_id) DO UPDATE SET
    name = EXCLUDED.name, sort_order = EXCLUDED.sort_order, company_id = EXCLUDED.company_id,
    parent_dept_id = EXCLUDED.parent_dept_id, dept_type = EXCLUDED.dept_type;

INSERT INTO department (dept_id, name, sort_order, company_id, parent_dept_id, dept_type) VALUES
    (1, '인사팀',     1, 1, 10, 'TEAM'),
    (4, '재무팀',     2, 1, 10, 'TEAM'),
    (5, '총무팀',     3, 1, 10, 'TEAM'),
    (2, '개발팀',     1, 1, 11, 'TEAM'),
    (6, '인프라팀',   2, 1, 11, 'TEAM'),
    (7, 'QA팀',       3, 1, 11, 'TEAM'),
    (3, '영업팀',     1, 1, 12, 'TEAM'),
    (8, '해외영업팀', 2, 1, 12, 'TEAM'),
    (9, '마케팅팀',   3, 1, 12, 'TEAM')
ON CONFLICT (dept_id) DO UPDATE SET
    name = EXCLUDED.name, sort_order = EXCLUDED.sort_order, company_id = EXCLUDED.company_id,
    parent_dept_id = EXCLUDED.parent_dept_id, dept_type = EXCLUDED.dept_type;

-- ===== 직위 (rank_order = 서열, 클수록 높음) =====
-- 기존 ID 1~4(사원·대리·과장·부장)는 유지하고 서열만 다시 매긴다
INSERT INTO position (position_id, name, rank_order) VALUES
    (1,  '사원',     1),
    (5,  '주임',     2),
    (2,  '대리',     3),
    (3,  '과장',     4),
    (6,  '차장',     5),
    (4,  '부장',     6),
    (7,  '이사',     7),
    (8,  '상무',     8),
    (9,  '전무',     9),
    (10, '대표이사', 10)
ON CONFLICT (position_id) DO UPDATE SET name = EXCLUDED.name, rank_order = EXCLUDED.rank_order;

-- ===== 직급 (이름 + 호봉) =====
-- G1~G6 각 1~5호봉, 30행. grade_id = (직급 - 1) * 5 + 호봉  (예: G3 2호봉 = 12)
-- G1 사원급, G2 대리급, G3 과장급, G4 차장급, G5 부장급, G6 임원급
-- payroll-service 의 pay_grade(직급별 기본급)가 grade_id 를 쓴다. 지금은 grade_id 1(G1 1호봉)에만 금액이 있다
INSERT INTO grade (grade_id, name, level)
SELECT (g - 1) * 5 + l, 'G' || g, l
FROM generate_series(1, 6) AS g, generate_series(1, 5) AS l
ON CONFLICT (grade_id) DO UPDATE SET name = EXCLUDED.name, level = EXCLUDED.level;

-- ===== 직책 =====
INSERT INTO duty (duty_id, name) VALUES
    (1, '팀원'),
    (2, '팀장'),
    (3, '본부장'),
    (4, '대표이사')
ON CONFLICT (duty_id) DO UPDATE SET name = EXCLUDED.name;

-- ===== 직무 (FAMILY 계열 > KIND 직종 > ROLE 직무) =====
-- 부모를 먼저 넣는다
INSERT INTO job_code (job_code_id, type, name, parent_id) VALUES
    (1,  'FAMILY', '경영지원', NULL),
    (4,  'FAMILY', 'IT',       NULL),
    (7,  'FAMILY', '영업',     NULL)
ON CONFLICT (job_code_id) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name, parent_id = EXCLUDED.parent_id;

INSERT INTO job_code (job_code_id, type, name, parent_id) VALUES
    (2,  'KIND', '인사',     1),
    (12, 'KIND', '재무',     1),
    (15, 'KIND', '총무',     1),
    (5,  'KIND', '개발',     4),
    (18, 'KIND', '인프라',   4),
    (20, 'KIND', 'QA',       4),
    (8,  'KIND', '국내영업', 7),
    (22, 'KIND', '해외영업', 7),
    (24, 'KIND', '마케팅',   7)
ON CONFLICT (job_code_id) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name, parent_id = EXCLUDED.parent_id;

INSERT INTO job_code (job_code_id, type, name, parent_id) VALUES
    (3,  'ROLE', 'HR 운영',      2),
    (10, 'ROLE', '채용',         2),
    (11, 'ROLE', '교육',         2),
    (13, 'ROLE', '회계',         12),
    (14, 'ROLE', '자금',         12),
    (16, 'ROLE', '총무',         15),
    (6,  'ROLE', '백엔드',       5),
    (17, 'ROLE', '프론트엔드',   5),
    (19, 'ROLE', '클라우드 운영', 18),
    (21, 'ROLE', '테스트',       20),
    (9,  'ROLE', '법인영업',     8),
    (23, 'ROLE', '해외영업',     22),
    (25, 'ROLE', '디지털마케팅', 24)
ON CONFLICT (job_code_id) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name, parent_id = EXCLUDED.parent_id;

-- ===== 사원 =====
-- 최초 관리자 (G1 1호봉)
INSERT INTO employee (emp_no, login_id, password_enc, name, dept_id, position_id, grade_id, duty_id,
                      emp_type, hire_type, job_family_id, job_kind_id, job_role_id, status, role)
VALUES ('DEV0001', 'admin@local.dev',
        '$2a$10$FV3KGfhQYQg73nblDCNKaeW8fCyirP10C0TRawzKL6AQZOvVfGR.u',
        '개발관리자', 1, 1, 1, 1, 'REGULAR', 'OPEN', 1, 2, 3, 'EMPLOYED', 'ADMIN')
ON CONFLICT (login_id) DO NOTHING;

-- 테스트용 사원 (소량). 결재선·문서함 등 여러 사원이 필요한 기능 확인용. 비밀번호는 모두 admin1234!
-- emp_id 를 101~ 로 고정한다. approval-service seed 가 이 ID 로 결재선을 만든다
-- 직급이 G1 1호봉이 아닌 사원은 payroll pay_grade 에 금액이 들어가기 전까지 급여 계약 조회가 안 될 수 있다
--   101 이서연  인사팀 대리 팀원  G2 2호봉(7)
--   102 최민준  인사팀 부장 팀장  G5 3호봉(23)
--   103 정하은  개발팀 사원 팀원  G1 2호봉(2)
--   104 강도윤  개발팀 과장 팀원  G3 3호봉(13)
--   105 윤지호  개발팀 부장 팀장  G5 4호봉(24)
--   106 한예린  영업팀 과장 팀장  G3 5호봉(15)
--   107 서동현  경영지원본부 이사 본부장  G6 2호봉(27)
--   108 임채원  기술본부 상무 본부장      G6 3호봉(28)
--   109 문태준  DEV COMPANY 대표이사      G6 5호봉(30)
INSERT INTO employee (emp_id, emp_no, login_id, password_enc, name, dept_id, position_id, grade_id, duty_id,
                      emp_type, hire_type, hire_date, job_family_id, job_kind_id, job_role_id,
                      gender, status, role)
VALUES
    (101, 'DEV0101', 'user1@local.dev', '$2a$10$FV3KGfhQYQg73nblDCNKaeW8fCyirP10C0TRawzKL6AQZOvVfGR.u',
     '이서연', 1, 2, 7,  1, 'REGULAR', 'OPEN',           '2022-03-02', 1, 2, 3,  'FEMALE', 'EMPLOYED', 'USER'),
    (102, 'DEV0102', 'user2@local.dev', '$2a$10$FV3KGfhQYQg73nblDCNKaeW8fCyirP10C0TRawzKL6AQZOvVfGR.u',
     '최민준', 1, 4, 23, 2, 'REGULAR', 'ROLL',           '2012-01-02', 1, 2, 3,  'MALE',   'EMPLOYED', 'USER'),
    (103, 'DEV0103', 'user3@local.dev', '$2a$10$FV3KGfhQYQg73nblDCNKaeW8fCyirP10C0TRawzKL6AQZOvVfGR.u',
     '정하은', 2, 1, 2,  1, 'REGULAR', 'OPEN',           '2025-07-01', 4, 5, 17, 'FEMALE', 'EMPLOYED', 'USER'),
    (104, 'DEV0104', 'user4@local.dev', '$2a$10$FV3KGfhQYQg73nblDCNKaeW8fCyirP10C0TRawzKL6AQZOvVfGR.u',
     '강도윤', 2, 3, 13, 1, 'REGULAR', 'ROLL',           '2018-09-03', 4, 5, 6,  'MALE',   'EMPLOYED', 'USER'),
    (105, 'DEV0105', 'user5@local.dev', '$2a$10$FV3KGfhQYQg73nblDCNKaeW8fCyirP10C0TRawzKL6AQZOvVfGR.u',
     '윤지호', 2, 4, 24, 2, 'REGULAR', 'ROLL',           '2011-03-02', 4, 5, 6,  'MALE',   'EMPLOYED', 'USER'),
    (106, 'DEV0106', 'user6@local.dev', '$2a$10$FV3KGfhQYQg73nblDCNKaeW8fCyirP10C0TRawzKL6AQZOvVfGR.u',
     '한예린', 3, 3, 15, 2, 'REGULAR', 'RECOMMENDATION', '2019-05-13', 7, 8, 9,  'FEMALE', 'EMPLOYED', 'USER'),
    (107, 'DEV0107', 'user7@local.dev', '$2a$10$FV3KGfhQYQg73nblDCNKaeW8fCyirP10C0TRawzKL6AQZOvVfGR.u',
     '서동현', 10, 7, 27, 3, 'REGULAR', 'ROLL',          '2008-03-03', 1, 2, 3,  'MALE',   'EMPLOYED', 'USER'),
    (108, 'DEV0108', 'user8@local.dev', '$2a$10$FV3KGfhQYQg73nblDCNKaeW8fCyirP10C0TRawzKL6AQZOvVfGR.u',
     '임채원', 11, 8, 28, 3, 'REGULAR', 'ROLL',          '2006-07-03', 4, 5, 6,  'FEMALE', 'EMPLOYED', 'USER'),
    (109, 'DEV0109', 'user9@local.dev', '$2a$10$FV3KGfhQYQg73nblDCNKaeW8fCyirP10C0TRawzKL6AQZOvVfGR.u',
     '문태준', 13, 10, 30, 4, 'REGULAR', 'ROLL',         '2001-01-02', 1, 15, 16, 'MALE',   'EMPLOYED', 'USER')
ON CONFLICT (emp_id) DO UPDATE SET
    emp_no = EXCLUDED.emp_no, login_id = EXCLUDED.login_id, password_enc = EXCLUDED.password_enc,
    name = EXCLUDED.name, dept_id = EXCLUDED.dept_id, position_id = EXCLUDED.position_id,
    grade_id = EXCLUDED.grade_id, duty_id = EXCLUDED.duty_id, emp_type = EXCLUDED.emp_type,
    hire_type = EXCLUDED.hire_type, hire_date = EXCLUDED.hire_date, job_family_id = EXCLUDED.job_family_id,
    job_kind_id = EXCLUDED.job_kind_id, job_role_id = EXCLUDED.job_role_id, gender = EXCLUDED.gender,
    status = EXCLUDED.status, role = EXCLUDED.role;

-- ===== 부서장 =====
-- 공석(NULL)도 일부러 둔다: 영업본부(본부장 공석 → 영업팀 결재는 대표이사로 올라감), 팀장 없는 팀 6개
UPDATE department d
SET head_emp_id = v.head_emp_id
FROM (VALUES (13, 109),     -- DEV COMPANY  대표이사 문태준
             (10, 107),     -- 경영지원본부 본부장 서동현
             (11, 108),     -- 기술본부     본부장 임채원
             (12, NULL),    -- 영업본부     공석
             (1,  102),     -- 인사팀       팀장 최민준
             (2,  105),     -- 개발팀       팀장 윤지호
             (3,  106),     -- 영업팀       팀장 한예린
             (4,  NULL), (5, NULL), (6, NULL), (7, NULL), (8, NULL), (9, NULL)
     ) AS v(dept_id, head_emp_id)
WHERE d.dept_id = v.dept_id;

-- 명시적 ID로 넣었으므로 시퀀스를 현재 최댓값 뒤로 맞춘다
SELECT setval('company_company_id_seq',   (SELECT MAX(company_id)  FROM company));
SELECT setval('department_dept_id_seq',   (SELECT MAX(dept_id)     FROM department));
SELECT setval('position_position_id_seq', (SELECT MAX(position_id) FROM position));
SELECT setval('grade_grade_id_seq',       (SELECT MAX(grade_id)    FROM grade));
SELECT setval('duty_duty_id_seq',         (SELECT MAX(duty_id)     FROM duty));
SELECT setval('job_code_job_code_id_seq', (SELECT MAX(job_code_id) FROM job_code));
SELECT setval('employee_emp_id_seq',      (SELECT MAX(emp_id)      FROM employee));
