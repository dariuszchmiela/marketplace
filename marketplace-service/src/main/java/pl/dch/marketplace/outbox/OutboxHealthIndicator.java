package pl.dch.marketplace.outbox;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Event publication (and thereby Kafka reachability), judged by its effect instead of a broker probe: if the oldest
 * unpublished outbox row is older than {@code outbox.health.max-oldest-pending-age}, publication is not keeping up
 * (Kafka down, publisher stuck, poison row). Uses the cached backlog snapshot of {@link OutboxMetrics}, so health
 * requests add no extra queries beyond one small set per snapshot interval.
 * <p>
 * Reports DEGRADED (shown in the {@code dependencies} group, ranked below UP for the public status and not part of
 * readiness). Kafka being down does <strong>not</strong> make the marketplace unready: checkouts keep working and
 * their events wait safely in the outbox — that is the point of the outbox.
 */
@Component("outbox")
class OutboxHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED", "events are not being published; they wait safely in the outbox");

    private final OutboxMetrics metrics;
    private final Duration maxOldestPendingAge;

    OutboxHealthIndicator(OutboxMetrics metrics,
                          @Value("${outbox.health.max-oldest-pending-age:60s}") Duration maxOldestPendingAge) {
        this.metrics = metrics;
        this.maxOldestPendingAge = maxOldestPendingAge;
    }

    @Override
    public Health health() {
        OutboxRepository.Backlog backlog = metrics.backlog();
        if (backlog == null) {
            return Health.unknown().withDetail("reason", "backlog could not be read").build();
        }
        boolean stalled = backlog.oldestAgeSeconds() > maxOldestPendingAge.toSeconds();
        return (stalled ? Health.status(DEGRADED) : Health.up())
                .withDetail("pending", backlog.pending())
                .withDetail("retrying", backlog.retrying())
                .withDetail("oldestPendingAgeSeconds", Math.round(backlog.oldestAgeSeconds()))
                .withDetail("maxOldestPendingAgeSeconds", maxOldestPendingAge.toSeconds())
                .build();
    }
}
