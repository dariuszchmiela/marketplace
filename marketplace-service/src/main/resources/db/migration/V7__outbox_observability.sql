-- Phase 6: outbox observability.

-- W3C trace context of the request/transaction that created the event, e.g.
-- 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 (always 55 characters). The publisher continues this trace
-- when it later sends the row to Kafka, so one trace spans: HTTP request -> outbox row -> Kafka -> consumer.
-- Only the traceparent is stored (no tracestate/baggage, no business data). NULL when tracing was not active.
-- It is deliberately NOT part of the JSON message: trace context travels in Kafka headers, not in the event schema.
ALTER TABLE outbox_event ADD COLUMN trace_parent VARCHAR(55);

-- Backlog metrics ask "how many rows are pending?" and "how old is the oldest one?" on every scrape. This partial
-- index contains only unpublished rows (it stays tiny while the publisher keeps up), so both queries are index-only /
-- first-row lookups instead of scanning the ever-growing history of published rows.
CREATE INDEX idx_outbox_pending_by_id ON outbox_event (id) WHERE published_at IS NULL;
