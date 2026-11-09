-- 헤더 위 제목·안내 행 (근무표의 'D 07:00~15:30 / E 15:00~23:30' 같은 근무 코드 범례)
ALTER TABLE migration_file ADD COLUMN preamble TEXT;
