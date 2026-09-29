package pl.dch.marketplace.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * The message format on Kafka (JSON). Stable envelope, type-specific {@code payload}.
 * <p>
 * Compatibility rules: within one {@code schemaVersion} changes are additive only (new optional fields;
 * consumers ignore unknown fields). Removing/renaming/retyping a field needs a new schemaVersion, which
 * consumers must explicitly support (unknown versions are rejected, not guessed).
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        int sequence,
        Instant occurredAt,
        Object payload
) {
}
