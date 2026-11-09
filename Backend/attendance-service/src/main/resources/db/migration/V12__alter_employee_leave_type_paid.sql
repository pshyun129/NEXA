-- 휴가 유형명(화면 표시)과 유급 여부(무급휴가 급여 공제) 추가. 휴가 서비스 조회 API의 leaveTypeName, isPaid
ALTER TABLE employee_leave ADD COLUMN leave_type_name VARCHAR(50) NOT NULL;
ALTER TABLE employee_leave ADD COLUMN is_paid BOOLEAN NOT NULL;
