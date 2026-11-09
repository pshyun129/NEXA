-- 엑셀 시트 선택·헤더 위치·합계 행, 셀 단위 수식·오류값
ALTER TABLE migration_file ADD COLUMN sheet_name       VARCHAR(255);
ALTER TABLE migration_file ADD COLUMN header_row       INTEGER;
ALTER TABLE migration_file ADD COLUMN total_rows       INTEGER[] NOT NULL DEFAULT '{}';   -- 합계·소계로 보고 뺀 행
ALTER TABLE migration_file ADD COLUMN rows_after_total INTEGER[] NOT NULL DEFAULT '{}';   -- 합계 행 아래 데이터 행

-- meta = {"f": {"원본 헤더": "수식"}, "e": {"원본 헤더": "#N/A"}} (수식·오류 셀이 없으면 NULL)
ALTER TABLE staging_row ADD COLUMN meta JSONB;
