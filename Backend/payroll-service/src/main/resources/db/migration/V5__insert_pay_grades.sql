-- 직급·호봉별 기본급 (운영에서도 쓰는 기준값). grade_id는 user-service grade.grade_id와 맞춘다 ((직급-1)*5 + 호봉)
-- 직급별 1호봉 금액에서 호봉마다 10만원씩 오른다. 이미 있는 직급(이관 등으로 들어온 값)은 바꾸지 않는다
INSERT INTO pay_grade (grade_id, base_amount)
SELECT (base.grade - 1) * 5 + step, base.amount + (step - 1) * 100000
FROM (VALUES (1, 3500000),
             (2, 4000000),
             (3, 4600000),
             (4, 5300000),
             (5, 6100000),
             (6, 7500000)) AS base(grade, amount),
     generate_series(1, 5) AS step
ON CONFLICT (grade_id) DO NOTHING;
