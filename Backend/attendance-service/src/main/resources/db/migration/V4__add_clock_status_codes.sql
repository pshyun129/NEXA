-- 출퇴근 상태 코드 (work_status_log에 출근/퇴근/퇴근취소 기록용)
INSERT INTO work_status_code (status_code, name, count_as_work) VALUES
                                                                    ('CLOCK_IN',         '출근',     true),
                                                                    ('CLOCK_OUT',        '퇴근',     false),
                                                                    ('CLOCK_OUT_CANCEL', '퇴근취소', false);