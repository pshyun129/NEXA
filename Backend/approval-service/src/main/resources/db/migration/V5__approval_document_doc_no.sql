-- 문서번호를 서버가 채번하도록 바꾼다. 형식: {양식}-{상신 연도}-{양식·연도별 일련번호 4자리} (예: LEAVE-2026-0001)
-- 연도는 서버·DB 시간대와 상관없이 한국 시간(Asia/Seoul) 기준 상신 연도

-- 양식·연도별 마지막 일련번호
CREATE TABLE document_number_counter (
    form_type  VARCHAR(255) NOT NULL,       -- FormType enum
    year       INT          NOT NULL,       -- 상신 연도
    last_no    INT          NOT NULL,       -- 마지막으로 발급한 일련번호
    PRIMARY KEY (form_type, year)
);

-- 기존 문서번호는 요청자가 임의로 보낸 값이라 중복이 있을 수 있으므로 같은 형식으로 다시 매긴다
-- (양식·연도별로 문서ID 순서대로 1부터)
WITH numbered AS (
    SELECT doc_id,
           COALESCE(form_type, 'GENERAL')                        AS form_type,
           EXTRACT(YEAR FROM COALESCE(drafted_at, now()) AT TIME ZONE 'Asia/Seoul')::INT AS year,
           ROW_NUMBER() OVER (
               PARTITION BY COALESCE(form_type, 'GENERAL'),
                            EXTRACT(YEAR FROM COALESCE(drafted_at, now()) AT TIME ZONE 'Asia/Seoul')
               ORDER BY doc_id)                                  AS seq
    FROM approval_document
)
UPDATE approval_document d
SET doc_no = n.form_type || '-' || n.year || '-' || lpad(n.seq::text, 4, '0')
FROM numbered n
WHERE d.doc_id = n.doc_id;

-- 다시 매긴 번호 다음부터 이어서 발급하도록 카운터를 맞춘다
INSERT INTO document_number_counter (form_type, year, last_no)
SELECT COALESCE(form_type, 'GENERAL'),
       EXTRACT(YEAR FROM COALESCE(drafted_at, now()) AT TIME ZONE 'Asia/Seoul')::INT,
       COUNT(*)
FROM approval_document
GROUP BY 1, 2;

ALTER TABLE approval_document
    ADD CONSTRAINT uk_approval_document_doc_no UNIQUE (doc_no);

COMMENT ON COLUMN approval_document.doc_no IS '문서번호. 서버가 채번 ({양식}-{상신 연도}-{양식·연도별 일련번호 4자리})';
