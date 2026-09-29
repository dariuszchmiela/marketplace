package pl.dch.marketplace.outbox;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes unpublished outbox rows: claim a bounded batch → send each to Kafka → mark it published.
 * <p>
 * One short transaction per batch holds the {@code FOR UPDATE SKIP LOCKED} claim, so other publisher instances
 * skip these rows while they are being sent. Unlike the checkout (no transaction during the payment call), this
 * background job deliberately keeps its claim open during the Kafka send; it is bounded by the batch size and
 * the producer timeouts, and it is not on any user request path.
 * <p>
 * Failure handling:
 * <ul>
 *   <li>send fails → the row stays unpublished with attempt_count/last_error and a backoff; the batch stops (the
 *       broker is probably unavailable, the next rows would only wait for the same timeout); later polls retry;</li>
 *   <li>send succeeds but the process dies (or the UPDATE fails) before commit → the claim rolls back, the row is
 *       still unpublished and is sent <strong>again</strong> by a later poll. This is the at-least-once window:
 *       consumers deduplicate by eventId. The outbox guarantees "never lost", not "exactly once".</li>
 * </ul>
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    public record BatchResult(int claimed, int published, int failed) {
    }

    private final OutboxRepository repository;
    private final EventSender sender;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties.Publisher properties;

    public OutboxPublisher(OutboxRepository repository, EventSender sender, TransactionTemplate transactionTemplate,
                           OutboxProperties.Publisher properties) {
        this.repository = repository;
        this.sender = sender;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    public BatchResult publishPendingBatch() {
        BatchResult result = transactionTemplate.execute(status -> {
            var batch = repository.claimBatch(properties.batchSize());
            int published = 0;
            for (OutboxRecord record : batch) {
                log.info("outbox.publish_attempt eventId={} eventType={} aggregateId={} attempt={}",
                        record.eventId(), record.eventType(), record.aggregateId(), record.attemptCount() + 1);
                try {
                    sender.send(record);
                } catch (RuntimeException ex) {
                    Duration retryAfter = backoff(record.attemptCount() + 1);
                    repository.markFailed(record.id(), ex.getMessage(), retryAfter);
                    log.warn("outbox.publish_failed eventId={} eventType={} aggregateId={} attempt={} retryAfterMs={} error=\"{}\"",
                            record.eventId(), record.eventType(), record.aggregateId(), record.attemptCount() + 1,
                            retryAfter.toMillis(), ex.getMessage());
                    return new BatchResult(batch.size(), published, 1);
                }
                repository.markPublished(record.id());
                published++;
                log.info("outbox.published eventId={} eventType={} aggregateId={} sequence={}",
                        record.eventId(), record.eventType(), record.aggregateId(), record.sequence());
            }
            return new BatchResult(batch.size(), published, 0);
        });
        return result;
    }

    /** Publishes batches until nothing is left (bounded by {@code maxBatches}); used by the scheduler and tests. */
    public int publishAllPending(int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            BatchResult result = publishPendingBatch();
            total += result.published();
            if (result.claimed() == 0 || result.failed() > 0) {
                break;
            }
        }
        return total;
    }

    Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 20);
        Duration candidate = properties.initialRetryBackoff().multipliedBy(multiplier);
        return candidate.compareTo(properties.maxRetryBackoff()) > 0 ? properties.maxRetryBackoff() : candidate;
    }
}
