package pl.dch.marketplace.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Business code records events here; it never talks to Kafka.
 * <p>
 * {@code MANDATORY}: an event can only be written inside the transaction that makes the business change it
 * describes. Calling this without a transaction is a bug and fails immediately.
 */
@Component
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;

    public OutboxWriter(OutboxRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, String aggregateId, String eventType, int schemaVersion, Object payload) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int sequence = repository.nextSequence(aggregateType, aggregateId);
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, schemaVersion, aggregateType, aggregateId,
                sequence, occurredAt, payload);
        repository.insert(eventId, aggregateType, aggregateId, eventType, schemaVersion, sequence,
                jsonMapper.writeValueAsString(envelope), occurredAt);
        log.info("outbox.created eventId={} eventType={} aggregateType={} aggregateId={} sequence={}",
                eventId, eventType, aggregateType, aggregateId, sequence);
        return eventId;
    }
}
