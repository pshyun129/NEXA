-- 결재 문서
CREATE TABLE approval_document (
    doc_id        BIGSERIAL PRIMARY KEY,
    doc_no        VARCHAR(255),
    form_type     VARCHAR(255),               -- FormType enum
    title         VARCHAR(255),
    status        VARCHAR(255),               -- DocumentStatus enum (INPROGRESS/APPROVED/REJECTED/CANCEL)
    drafted_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    drafter_id    BIGINT                      -- user-service employee.emp_id (FK 없음)
);

-- 결재선
CREATE TABLE approval_line (
    line_id       BIGSERIAL PRIMARY KEY,
    step          SMALLINT,
    role          VARCHAR(255),               -- LineRole enum (APPROVER/AGREEMENT/REFERENCE)
    action        VARCHAR(255),               -- LineAction enum (AGREE/DISAGREE/PENDING)
    acted_at      TIMESTAMPTZ,
    doc_id        BIGINT REFERENCES approval_document(doc_id),
    emp_id        BIGINT                      -- user-service employee.emp_id (FK 없음)
);

-- 결재 변경 이력
CREATE TABLE approval_history (
    history_id    BIGSERIAL PRIMARY KEY,
    doc_id        BIGINT NOT NULL REFERENCES approval_document(doc_id),
    round         INT,
    actor_id      BIGINT NOT NULL,            -- user-service employee.emp_id (FK 없음)
    action        VARCHAR(255),               -- HistoryAction enum
    target_emp_id BIGINT,
    comment       TEXT,
    occurred_at   TIMESTAMPTZ
);

CREATE INDEX idx_approval_line_doc_id ON approval_line(doc_id);
CREATE INDEX idx_approval_line_emp_id ON approval_line(emp_id);
CREATE INDEX idx_approval_history_doc_id ON approval_history(doc_id);
