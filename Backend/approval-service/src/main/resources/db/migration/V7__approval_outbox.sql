-- 트랜잭셔널 아웃박스: 결재 처리와 같은 트랜잭션에서 이벤트를 기록하고, 발행기가 나중에 Kafka로 보낸다
-- (DB 커밋과 Kafka 발행 사이에 이벤트가 사라지는 것을 막는다)
CREATE TABLE approval_outbox (
    id            BIGSERIAL    PRIMARY KEY,           -- 발행 순서
    event_id      UUID         NOT NULL,              -- 이벤트마다 고유. 수신 측 중복 판단용
    topic         VARCHAR(255) NOT NULL,              -- 보낼 토픽
    message_key   VARCHAR(255) NOT NULL,              -- Kafka 메시지 키 (같은 키 → 같은 파티션 → 순서 보장)
    payload       TEXT         NOT NULL,              -- 이벤트 JSON
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,                        -- NULL 이면 아직 발행 전
    CONSTRAINT uk_approval_outbox_event_id UNIQUE (event_id)
);

-- 발행기는 미발행 행만 찾는다. 부분 인덱스라 발행된 행이 쌓여도 인덱스가 커지지 않는다
CREATE INDEX idx_approval_outbox_unpublished ON approval_outbox (id) WHERE published_at IS NULL;
