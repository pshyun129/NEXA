CREATE TABLE attendance_anomaly (
                                    anomaly_id   BIGSERIAL PRIMARY KEY,
                                    att_id       BIGINT       NOT NULL REFERENCES attendance_daily (att_id),
                                    anomaly_type VARCHAR(255) NOT NULL,  -- AnomalyType enum
                                    created_at   TIMESTAMPTZ  NOT NULL
);