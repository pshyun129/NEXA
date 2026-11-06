-- 휴가 유형명을 휴가 서비스(leave_type.name VARCHAR(100))와 같은 길이로 늘린다 (50자를 넘는 유형명 저장 실패)
ALTER TABLE employee_leave ALTER COLUMN leave_type_name TYPE VARCHAR(100);
