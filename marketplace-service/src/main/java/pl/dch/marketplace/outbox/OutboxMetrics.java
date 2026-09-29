package pl.dch.marketplace.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers "is asynchronous event publication healthy?".
 * <ul>
 *   <li>{@code outbox.pending.count} — unpublished rows. Grows when Kafka is down or the publisher is stuck;</li>
 *   <li>{@code outbox.oldest.pending.age} (seconds) — the outbox lag: how late downstream consumers are, at least.
 *       The best alerting signal: a large count may just be a burst, an old row means publication is not moving;</li>
 *   <li>{@code outbox.pending.retrying} — pending rows that already failed at least once;</li>
 *   <li>{@code outbox.publish.success / failure{event_type}} — sends acknowledged / rejected by Kafka;</li>
 *   <li>{@code outbox.batch.duration{outcome}} — one claim transaction, i.e. how long the claimed rows stay locked
 *       while the publisher waits for Kafka acknowledgements (the known trade-off of this simple design);</li>
 *   <li>{@code outbox.batch.size} — rows claimed per batch;</li>
 *   <li>per-record Kafka send + ack time: the {@code outbox.publish} observation timer (see {@link OutboxTracing}).</li>
 * </ul>
 * The gauges read a snapshot that is refreshed at most every {@code snapshotMaxAge}: Prometheus, the health endpoint
 * and several gauges can ask as often as they like, the database sees at most one small query set per interval.
 * No event ids or order ids are used as tags.
 */
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);
    static final int BACKLOG_COUNT_LIMIT = 100_000;

    private final OutboxRepository repository;
    private final MeterRegistry registry;
    private final Duration snapshotMaxAge;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final DistributionSummary batchSize;

    private record Snapshot(OutboxRepository.Backlog backlog, Instant takenAt) {
    }

    public OutboxMetrics(OutboxRepository repository, MeterRegistry registry, Duration snapshotMaxAge, Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.snapshotMaxAge = snapshotMaxAge;
        this.clock = clock;
        Gauge.builder("outbox.pending.count", this, metrics -> metrics.gaugeValue(OutboxRepository.Backlog::pending))
                .description("Unpublished outbox rows (capped at " + BACKLOG_COUNT_LIMIT + ")")
                .register(registry);
        Gauge.builder("outbox.pending.retrying", this, metrics -> metrics.gaugeValue(OutboxRepository.Backlog::retrying))
                .description("Unpublished outbox rows that failed at least once")
                .register(registry);
        Gauge.builder("outbox.oldest.pending.age", this, metrics -> metrics.gaugeValue(OutboxRepository.Backlog::oldestAgeSeconds))
                .description("Age of the oldest unpublished outbox row (outbox lag); 0 when nothing is pending")
                .baseUnit("seconds")
                .register(registry);
        this.batchSize = DistributionSummary.builder("outbox.batch.size")
                .description("Rows claimed per publisher batch")
                .register(registry);
    }

    /** Current backlog, from the cached snapshot; null if it could not be read (database unavailable). */
    public OutboxRepository.@Nullable Backlog backlog() {
        Snapshot current = snapshot.get();
        Instant now = clock.instant();
        if (current == null || current.takenAt().plus(snapshotMaxAge).isBefore(now)) {
            try {
                current = new Snapshot(repository.backlog(BACKLOG_COUNT_LIMIT), now);
                snapshot.set(current);
            } catch (RuntimeException ex) {
                log.warn("outbox.backlog_query_failed error=\"{}\"", ex.getMessage());
                return null;
            }
        }
        return current.backlog();
    }

    /** Creates the counters of the given event types with value 0 (so rate()/increase() see their first increments). */
    public void registerEventTypes(String... eventTypes) {
        for (String eventType : eventTypes) {
            successCounter(eventType);
            failureCounter(eventType);
        }
    }

    void published(String eventType) {
        successCounter(eventType).increment();
    }

    void failed(String eventType) {
        failureCounter(eventType).increment();
    }

    void batch(OutboxPublisher.BatchResult result, Duration elapsed) {
        String outcome = result.claimed() == 0 ? "empty" : result.failed() > 0 ? "failed" : "published";
        Timer.builder("outbox.batch.duration")
                .description("Duration of one claim transaction: claimed rows stay locked while waiting for Kafka")
                .tag("outcome", outcome)
                .register(registry)
                .record(elapsed);
        if (result.claimed() > 0) {
            batchSize.record(result.claimed());
        }
    }

    private Counter successCounter(String eventType) {
        return Counter.builder("outbox.publish.success").description("Outbox rows acknowledged by Kafka")
                .tag("event_type", eventType).register(registry);
    }

    private Counter failureCounter(String eventType) {
        return Counter.builder("outbox.publish.failure").description("Outbox publication attempts that failed")
                .tag("event_type", eventType).register(registry);
    }

    private double gaugeValue(ToDoubleFunction<OutboxRepository.Backlog> value) {
        OutboxRepository.Backlog backlog = backlog();
        return backlog == null ? Double.NaN : value.applyAsDouble(backlog);
    }
}
