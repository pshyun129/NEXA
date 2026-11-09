-- attendance-service 기준 데이터 스키마. Hibernate ddl-auto=validate 기준으로 엔티티와 1:1로 맞춘다.

CREATE TABLE work_status_code (
                                  status_code   VARCHAR(20) PRIMARY KEY,
                                  name          VARCHAR(30) NOT NULL,
                                  count_as_work BOOLEAN     NOT NULL
);

CREATE TABLE work_group (
                            work_group_id     BIGSERIAL PRIMARY KEY,
                            name              VARCHAR(50)  NOT NULL,
                            work_type         VARCHAR(255) NOT NULL,  -- WorkType enum
                            start_time        TIME,
                            end_time          TIME,
                            break_minutes     INTEGER      NOT NULL,
                            work_days         INTEGER      NOT NULL,
                            daily_std_minutes INTEGER      NOT NULL,
                            weekly_std_hours  INTEGER      NOT NULL,
                            weekly_max_hours  INTEGER      NOT NULL,
                            is_default        BOOLEAN      NOT NULL,
                            created_at        TIMESTAMPTZ  NOT NULL,
                            updated_at        TIMESTAMPTZ  NOT NULL
);

CREATE TABLE employee_work_group (
                                     id            BIGSERIAL PRIMARY KEY,
                                     work_group_id BIGINT      NOT NULL REFERENCES work_group (work_group_id),
                                     emp_id        BIGINT      NOT NULL,
                                     start_date    DATE        NOT NULL,
                                     end_date      DATE,
                                     created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE holiday (
                         holiday_date DATE PRIMARY KEY,
                         name         VARCHAR(50)  NOT NULL,
                         type         VARCHAR(255) NOT NULL  -- HolidayType enum
);