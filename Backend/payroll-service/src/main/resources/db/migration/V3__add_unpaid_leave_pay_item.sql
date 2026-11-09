-- 무급휴가 차감 (지급 항목, 음수 금액). 지급합계에서 빠져야 세금·4대보험도 줄어든 금액 기준으로 계산된다.
-- 지급 항목 마지막(명절·휴가수당 7) 뒤에 두려고 공제 항목 순서를 하나씩 미룬다
UPDATE pay_item SET sort_order = sort_order + 1 WHERE kind = 'DEDUCTION';

INSERT INTO pay_item (item_code, name, kind, sort_order) VALUES
    ('UNPAID_LEAVE_DEDUCTION', '무급휴가 차감', 'EARN', 8);
