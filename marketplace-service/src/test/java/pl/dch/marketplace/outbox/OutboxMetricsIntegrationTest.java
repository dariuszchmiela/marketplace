package pl.dch.marketplace.outbox;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.dch.marketplace.IntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * "Is asynchronous publication healthy?" — the backlog gauges grow while Kafka rejects sends and drain when it
 * accepts them again. The Kafka outage is simulated by a sender that fails, exactly like a broker that cannot be
 * reached (the real outage is part of the local smoke test).
 */
class OutboxMetricsIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private EventSender kafkaSender;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Autowired
    private OutboxTracing outboxTracing;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void backlogGrowsDuringAnOutageAndDrainsAfterRecovery() throws Exception {
        OutboxPublisher kafkaDown = publisherWith(record -> {
            throw new EventSender.EventPublicationException("Kafka unreachable (simulated)", null);
        });
        drain();   // start from an empty backlog (rows left by other tests)
        double pendingBefore = gauge("outbox.pending.count");
        double failuresBefore = counter("outbox.publish.failure", "OrderCreated");
        double successesBefore = counter("outbox.publish.success", "OrderCreated");

        appendEvents(3);
        Thread.sleep(50);
        kafkaDown.publishPendingBatch();

        assertThat(gauge("outbox.pending.count") - pendingBefore).isEqualTo(3);
        assertThat(gauge("outbox.oldest.pending.age")).isPositive();
        assertThat(gauge("outbox.pending.retrying")).isGreaterThanOrEqualTo(1);
        assertThat(counter("outbox.publish.failure", "OrderCreated") - failuresBefore).isEqualTo(1);
        assertThat(meterRegistry.get("outbox.batch.duration").tag("outcome", "failed").timer().count()).isPositive();

        // Kafka is back (the failed row waits for its short backoff in tests).
        Thread.sleep(properties.publisher().initialRetryBackoff().toMillis() + 50);
        drain();

        assertThat(gauge("outbox.pending.count")).isZero();
        assertThat(gauge("outbox.oldest.pending.age")).isZero();
        assertThat(counter("outbox.publish.success", "OrderCreated") - successesBefore).isEqualTo(3);
        assertThat(meterRegistry.get("outbox.publish").tag("event_type", "OrderCreated").timer().count())
                .as("per-record Kafka send + ack time (observation timer)").isPositive();
        assertThat(meterRegistry.get("outbox.batch.size").summary().count()).isPositive();
    }

    @Test
    void operatorsSeeTheBacklogInTheDependencyHealth() throws Exception {
        drain();
        appendEvents(2);

        mockMvc.perform(get("/actuator/health/dependencies").header(HttpHeaders.AUTHORIZATION, "Bearer " + MANAGEMENT_TOKEN))
                .andExpect(jsonPath("$.components.outbox.status").value("UP"))   // young rows: not stalled yet
                .andExpect(jsonPath("$.components.outbox.details.pending").value(2));
        drain();
    }

    @Test
    void theBacklogQueryDoesNotWaitForRowsClaimedByThePublisher() throws Exception {
        drain();
        appendEvents(1);
        // A publisher holds its FOR UPDATE claim while waiting for Kafka; metrics must not block behind it (MVCC read).
        OutboxPublisher slowKafka = publisherWith(record -> sleep(Duration.ofSeconds(2)));
        Thread claim = Thread.ofVirtual().start(slowKafka::publishPendingBatch);
        Thread.sleep(300);

        long start = System.nanoTime();
        double pending = gauge("outbox.pending.count");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(pending).isEqualTo(1);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        claim.join();
    }

    private void appendEvents(int count) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                outboxWriter.append("Order", "metrics-" + UUID.randomUUID(), "OrderCreated", 1, Map.of("n", i));
            }
        });
    }

    private void drain() {
        publisherWith(kafkaSender).publishAllPending(50);
        // Rows of other tests that are still in their backoff window are not drained here: mark them published.
        jdbcTemplate.update("update outbox_event set published_at = clock_timestamp() where published_at is null");
    }

    private OutboxPublisher publisherWith(EventSender sender) {
        return new OutboxPublisher(repository, sender, new TransactionTemplate(transactionManager), properties.publisher(),
                outboxMetrics, outboxTracing);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private double counter(String name, String eventType) {
        Counter counter = meterRegistry.find(name).tag("event_type", eventType).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
