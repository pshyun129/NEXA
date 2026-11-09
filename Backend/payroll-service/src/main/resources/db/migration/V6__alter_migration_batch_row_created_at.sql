-- 이관 기록 시각을 다른 급여 테이블과 같은 TIMESTAMPTZ로 맞춘다 (기존 값은 DB 시간대 기준으로 변환)
ALTER TABLE migration_batch_row ALTER COLUMN created_at TYPE TIMESTAMPTZ;
