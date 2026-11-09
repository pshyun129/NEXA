-- 급여 항목 마스터 (다우오피스 명세서 항목 기준, 계산 가능한 공제 항목만)
-- item_code는 급여 계산 코드에서 항목을 찾는 키로 사용한다.
INSERT INTO pay_item (item_code, name, kind, sort_order) VALUES
                                                             -- 지급
                                                             ('BASE_PAY',             '기본급',       'EARN',      1),
                                                             ('OT_EXTENDED_PAY',      '연장근로수당', 'EARN',      2),
                                                             ('OT_NIGHT_PAY',         '야간근로수당', 'EARN',      3),
                                                             ('OT_HOLIDAY_PAY',       '휴일근로수당', 'EARN',      4),
                                                             ('ANNUAL_LEAVE_PAY',     '연차수당',     'EARN',      5),
                                                             ('BONUS',                '상여금',       'EARN',      6),
                                                             ('HOLIDAY_BONUS',        '명절·휴가수당', 'EARN',     7),
                                                             -- 공제
                                                             ('INCOME_TAX',           '근로소득세',   'DEDUCTION', 8),
                                                             ('LOCAL_INCOME_TAX',     '근로지방소득세', 'DEDUCTION', 9),
                                                             ('NATIONAL_PENSION',     '국민연금',     'DEDUCTION', 10),
                                                             ('HEALTH_INSURANCE',     '건강보험',     'DEDUCTION', 11),
                                                             ('LONG_TERM_CARE',       '장기요양보험', 'DEDUCTION', 12),
                                                             ('EMPLOYMENT_INSURANCE', '고용보험',     'DEDUCTION', 13);