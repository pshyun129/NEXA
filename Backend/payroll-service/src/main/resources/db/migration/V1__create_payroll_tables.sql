-- payroll-service 스키마. Hibernate ddl-auto=validate 기준으로 엔티티와 1:1로 맞춘다.

CREATE TABLE pay_item (
                          item_id    BIGSERIAL PRIMARY KEY,
                          item_code  VARCHAR(30)  NOT NULL,
                          name       VARCHAR(50)  NOT NULL,
                          kind       VARCHAR(255) NOT NULL,  -- PayItemKind enum (EARN, DEDUCTION)
                          sort_order INTEGER      NOT NULL
);

CREATE TABLE pay_grade (
                           grade_id    BIGINT PRIMARY KEY,     -- user-service grade.grade_id
                           base_amount INTEGER NOT NULL
);

CREATE TABLE payslip (
                         payslip_id        BIGSERIAL PRIMARY KEY,
                         emp_id            BIGINT      NOT NULL,
                         pay_year          SMALLINT    NOT NULL,
                         pay_month         SMALLINT,
                         pay_date          DATE,
                         emp_name          VARCHAR(50) NOT NULL,
                         dept_name         VARCHAR(50) NOT NULL,
                         position_name     VARCHAR(50),
                         hire_date         DATE        NOT NULL,
                         work_days         SMALLINT    NOT NULL,
                         total_min         INTEGER     NOT NULL,
                         regular_min       INTEGER     NOT NULL,
                         ot_extended_min   INTEGER     NOT NULL,
                         ot_night_min      INTEGER     NOT NULL,
                         ot_holiday_min    INTEGER     NOT NULL,
                         leave_min         INTEGER     NOT NULL,
                         total_pay         INTEGER,
                         total_deduction   INTEGER,
                         net_pay           INTEGER,
                         created_at        TIMESTAMPTZ NOT NULL
);

CREATE TABLE payslip_item (
                              id         BIGSERIAL PRIMARY KEY,
                              payslip_id BIGINT  NOT NULL REFERENCES payslip (payslip_id),
                              item_id    BIGINT  NOT NULL REFERENCES pay_item (item_id),
                              amount     INTEGER NOT NULL
);