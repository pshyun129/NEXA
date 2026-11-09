-- 매핑 추천 출처(SYNONYM·LLM·USER)와 LLM 추천 근거
ALTER TABLE column_mapping ADD COLUMN suggested_by VARCHAR(20);
ALTER TABLE column_mapping ADD COLUMN reason VARCHAR(300);
