-- Phase 4: transactional outbox.
--
-- A row is written in the SAME transaction as the business change it describes (order created, paid, ...),
-- so both commit or both roll back. A separate publisher later sends unpublished rows to Kafka and marks them.
-- Delivery is therefore at-least-once: a crash after the Kafka send but before published_at is stored sends
-- the same event again, and consumers must deduplicate by event_id.
CREATE TABLE outbox_event
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    VARCHAR(64) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    schema_version  INTEGER     NOT NULL CHECK (schema_version > 0),
    -- 1, 2, 3, ... per aggregate: lets consumers detect stale or out-of-order events.
    sequence        INTEGER     NOT NULL CHECK (sequence > 0),
    -- The complete message (envelope + payload) exactly as it is sent to Kafka. JSON (not JSONB) keeps the text as written.
    payload         JSON        NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ,
    attempt_count   INTEGER     NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_attempt_at TIMESTAMPTZ,
    -- Backoff after a failed publication; NULL = may be published now.
    next_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(500),
    CONSTRAINT uk_outbox_aggregate_sequence UNIQUE (aggregate_type, aggregate_id, sequence)
);

-- The publisher only ever looks at unpublished rows; the partial index stays small however large the table grows.
CREATE INDEX idx_outbox_unpublished ON outbox_event (aggregate_type, aggregate_id, id) WHERE published_at IS NULL;
