package pl.dch.marketplace.outbox;

import java.util.UUID;

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
        int attemptCount
) {
}
