-- migration-service 의 급여 이관(직급별 기본급 / 지난 급여명세서 upsert, batch 되돌리기)을 받기 위한 스키마.

-- 1) batch 별 적재 기록. summary(적재 행 수)와 되돌리기(만든 행 삭제)에 쓴다.
--    이관은 이미 있는 기본급·명세서를 바꾸지 않으므로 수정 전 값은 남기지 않는다 (EXISTING 은 대사용 기록)
CREATE TABLE migration_batch_row (
    id         BIGSERIAL PRIMARY KEY,
    batch_id   VARCHAR(100) NOT NULL,
    entity     VARCHAR(20)  NOT NULL,   -- PAY_GRADE / PAYSLIP
    target_id  BIGINT       NOT NULL,   -- grade_id / payslip_id
    action     VARCHAR(10)  NOT NULL,   -- CREATED / EXISTING
    row_no     INTEGER,
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_migration_batch_row UNIQUE (batch_id, entity, target_id)
);

-- 2) 이관한 급여대장에 흔한 지급·공제 항목. 계산 코드는 쓰지 않고 지난 명세서를 옮길 때만 쓴다.
--    지급 항목(무급휴가 차감 8) 뒤에 두려고 공제 항목 순서를 셋 미룬다
UPDATE pay_item SET sort_order = sort_order + 3 WHERE kind = 'DEDUCTION';

INSERT INTO pay_item (item_code, name, kind, sort_order) VALUES
    ('POSITION_ALLOWANCE', '직책수당', 'EARN', 9),
    ('MEAL_ALLOWANCE',     '식대',     'EARN', 10),
    ('OTHER_EARN',         '기타수당', 'EARN', 11),
    ('OTHER_DEDUCTION',    '기타공제', 'DEDUCTION', 18);  -- 공제 마지막 (고용보험 17 뒤)
