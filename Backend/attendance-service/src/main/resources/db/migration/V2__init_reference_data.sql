-- 근무 상태 코드 (다우오피스 기본 상태 + 휴가)
INSERT INTO work_status_code (status_code, name, count_as_work) VALUES
                                                                    ('WORKING',     '업무',     true),
                                                                    ('OFF_WORKING', '업무종료', false),
                                                                    ('LEAVE',       '휴가',     false);

-- 기본 근무그룹 (다우오피스 기본그룹 기준: 09:00~18:00, 점심 60분, 주 40h / 최대 52h)
-- work_days: 요일 비트마스크 (월=1, 화=2, 수=4, 목=8, 금=16, 토=32, 일=64) → 월~금 = 31
INSERT INTO work_group (name, work_type, start_time, end_time, break_minutes, work_days,
                        daily_std_minutes, weekly_std_hours, weekly_max_hours, is_default,
                        created_at, updated_at)
VALUES ('기본그룹', 'FIXED', '09:00', '18:00', 60, 31, 480, 40, 52, true, now(), now());