CREATE TABLE attendance_request (
                                    att_req_id      BIGSERIAL PRIMARY KEY,
                                    emp_id          BIGINT       NOT NULL,
                                    req_type        VARCHAR(255) NOT NULL,  -- AttendanceRequestType enum
                                    target_date     DATE         NOT NULL,
                                    subst_date      DATE,
                                    reason          VARCHAR(500) NOT NULL,
                                    approval_doc_id BIGINT,
                                    doc_no          BIGINT,
                                    status          VARCHAR(255) NOT NULL,  -- AttendanceRequestStatus enum
                                    created_at      TIMESTAMPTZ  NOT NULL,
                                    completed_at    TIMESTAMPTZ
);

CREATE TABLE attendance_request_item (
                                         item_id       BIGSERIAL PRIMARY KEY,
                                         att_req_id    BIGINT      NOT NULL REFERENCES attendance_request (att_req_id),
                                         status_code   VARCHAR(20) NOT NULL REFERENCES work_status_code (status_code),
                                         target_log_id BIGINT,
                                         start_at      TIMESTAMPTZ NOT NULL,
                                         end_at        TIMESTAMPTZ,
                                         action        VARCHAR(255)            -- RequestItemAction enum
);