-- Owned by order-activity-service (schema order_activity). Built only from order events.

-- Idempotent consumer: an event id is inserted in the same local transaction as its effect.
-- A redelivered event finds its id already here and is acknowledged without repeating the effect.
CREATE TABLE processed_event
(
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    order_id     BIGINT      NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Current view of each order, eventually consistent with the marketplace.
CREATE TABLE order_activity
(
    order_id       BIGINT PRIMARY KEY,
    current_status VARCHAR(32)    NOT NULL,
    total          NUMERIC(12, 2) NOT NULL,
    currency       CHAR(3)        NOT NULL,
    last_event_id  UUID           NOT NULL,
    -- Sequence of the last applied event: older/replayed events (lower sequence) are ignored.
    last_sequence  INTEGER        NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL
);

-- Activity history ("order accepted", "payment confirmed", ...): the notification-like side effect.
CREATE TABLE order_activity_entry
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    event_id    UUID         NOT NULL UNIQUE,
    event_type  VARCHAR(64)  NOT NULL,
    sequence    INTEGER      NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    message     VARCHAR(200) NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    recorded_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_activity_entry_order ON order_activity_entry (order_id, id);
