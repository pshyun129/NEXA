CREATE TABLE work_status_log (
                                 log_id      BIGSERIAL PRIMARY KEY,
                                 status_code VARCHAR(20)  NOT NULL REFERENCES work_status_code (status_code),
                                 emp_id      BIGINT       NOT NULL,
                                 work_date   DATE         NOT NULL,
                                 start_at    TIMESTAMPTZ  NOT NULL,
                                 source      VARCHAR(255) NOT NULL,  -- WorkStatusSource enum
                                 created_by  BIGINT       NOT NULL,
                                 reason      VARCHAR(255),
                                 is_deleted  BOOLEAN      NOT NULL,
                                 created_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE attendance_daily (
                                  att_id            BIGSERIAL PRIMARY KEY,
                                  work_group_id     BIGINT      NOT NULL REFERENCES work_group (work_group_id),
                                  emp_id            BIGINT      NOT NULL,
                                  work_date         DATE        NOT NULL,
                                  clock_in          TIMESTAMPTZ,
                                  clock_out         TIMESTAMPTZ,
                                  total_min         INTEGER     NOT NULL,
                                  regular_min       INTEGER     NOT NULL,
                                  break_min         INTEGER     NOT NULL,
                                  excluded_min      INTEGER     NOT NULL,
                                  night_min         INTEGER     NOT NULL,
                                  ot_extended_min   INTEGER     NOT NULL,
                                  ot_night_min      INTEGER     NOT NULL,
                                  ot_holiday_min    INTEGER     NOT NULL,
                                  approved_ot_min   INTEGER     NOT NULL,
                                  unapproved_ot_min INTEGER     NOT NULL,
                                  is_confirmed      BOOLEAN     NOT NULL,
                                  is_holiday        BOOLEAN     NOT NULL,
                                  updated_at        TIMESTAMPTZ NOT NULL,
                                  CONSTRAINT uk_attendance_daily_emp_work_date UNIQUE (emp_id, work_date)
);