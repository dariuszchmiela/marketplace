package pl.dch.marketplace.outbox;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An unpublished outbox row claimed by the publisher. {@code payload} is the complete message to send.
 */
public record OutboxRecord(
        long id,
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        int schemaVersion,
        int sequence,
        String payload,
        int attemptCount,
        /* W3C traceparent of the transaction that created the event; null if tracing was not active */
        @Nullable String traceParent
) {
}
