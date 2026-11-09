-- 부서 유형(dept_type)과 부서장(head_emp_id)이 비어 있는 부서를 한 번 채운다.
-- V3 는 컬럼만 추가해서, 그 전에 들어 있던 부서는 값이 비어 있다 → 결재선 자동 결정이 409(APPROVAL_022)
-- 이미 값이 있는 부서는 건드리지 않는다. 판단이 애매하면 비워 두고 관리자가 직접 지정한다.
-- (빈 DB 에서는 아무것도 바꾸지 않는다. dev seed 는 이 마이그레이션 뒤에 실행되며 값을 직접 넣는다)

-- 1) 부서 유형: 트리 깊이로 정한다. 최상위 = 회사, 그 바로 아래 = 본부, 나머지 = 팀
--    순환 참조 등으로 최상위에서 닿지 않는 부서는 비워 둔다
WITH RECURSIVE tree AS (
    SELECT dept_id, 0 AS depth
    FROM department
    WHERE parent_dept_id IS NULL
    UNION ALL
    SELECT d.dept_id, t.depth + 1
    FROM department d
             JOIN tree t ON d.parent_dept_id = t.dept_id
    WHERE t.depth < 20
)
UPDATE department d
SET dept_type = CASE t.depth WHEN 0 THEN 'COMPANY' WHEN 1 THEN 'DIVISION' ELSE 'TEAM' END
FROM tree t
WHERE d.dept_id = t.dept_id
  AND d.dept_type IS NULL;

-- 2) 부서장: 그 부서 재직자 중 부서 유형에 맞는 직책(회사=대표이사, 본부=본부장, 팀=팀장)인 사람.
--    후보가 정확히 한 명일 때만 지정한다 (없거나 여러 명이면 공석으로 두고 관리자가 지정)
UPDATE department d
SET head_emp_id = c.emp_id
FROM (SELECT e.dept_id, MIN(e.emp_id) AS emp_id
      FROM employee e
               JOIN duty du ON du.duty_id = e.duty_id
               JOIN department dd ON dd.dept_id = e.dept_id
      WHERE e.status = 'EMPLOYED'
        AND du.name = CASE dd.dept_type
                          WHEN 'COMPANY' THEN '대표이사'
                          WHEN 'DIVISION' THEN '본부장'
                          WHEN 'TEAM' THEN '팀장'
                      END
      GROUP BY e.dept_id
      HAVING COUNT(*) = 1) c
WHERE d.dept_id = c.dept_id
  AND d.head_emp_id IS NULL;
