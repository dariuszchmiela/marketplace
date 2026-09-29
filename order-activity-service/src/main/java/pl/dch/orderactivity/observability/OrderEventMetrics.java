package pl.dch.orderactivity.observability;

import java.time.Duration;
import java.util.Set;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What the consumer did with the events — the business side of "is the projection keeping up and correct?".
 * <ul>
 *   <li>{@code order.events.processed{eventType}} — applied to the projection;</li>
 *   <li>{@code order.events.duplicate{eventType}} — already processed (outbox re-send, redelivery): skipped;</li>
 *   <li>{@code order.events.stale{eventType}} — older than the applied state (replay): skipped;</li>
 *   <li>{@code order.events.retry{eventType}} — a failed attempt with a transient failure: redelivered, until the
 *       retries are exhausted (then also counted as dead_lettered);</li>
 *   <li>{@code order.events.dead_lettered{eventType, reason}} — sent to the DLT: {@code permanent} (invalid event) or
 *       {@code retries_exhausted};</li>
 *   <li>{@code order.events.processing{eventType, result}} — time per delivery attempt (parse + DB transaction).</li>
 * </ul>
 * Kafka's own consumer lag (records not yet fetched/committed per partition) comes from the Kafka client metrics
 * ({@code kafka_consumer_fetch_manager_records_lag_max}). Lag says "how far behind"; these counters say "what happened
 * to what was consumed" — lag 0 with a growing DLT still means a projection that is missing orders.
 * <p>
 * {@code eventType} is read from a message header, i.e. from outside: unknown values are mapped to {@code other} so a
 * buggy or malicious producer cannot create unbounded label values. Never order ids or event ids.
 */
@Component
public class OrderEventMetrics {

    public static final Set<String> KNOWN_EVENT_TYPES =
            Set.of("OrderCreated", "OrderPaid", "OrderPaymentFailed", "OrderPaymentUnknown");

    public enum Result { APPLIED, DUPLICATE, STALE, FAILED }

    private final MeterRegistry registry;

    public OrderEventMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Counters exist from the start (value 0), so rate()/increase() and alerts see their first increments; e.g. the
        // first dead-lettered event would otherwise appear as a new series with value 1 and increase() would report 0.
        for (String eventType : KNOWN_EVENT_TYPES) {
            counter("order.events.processed", "Events applied to the projection", eventType);
            counter("order.events.duplicate", "Already processed events that were skipped", eventType);
            counter("order.events.stale", "Events older than the applied state that were skipped", eventType);
            counter("order.events.retry", "Failed delivery attempts with a transient failure", eventType);
            deadLetteredCounter(eventType, true);
            deadLetteredCounter(eventType, false);
        }
        internalApiRejectedCounter("MISSING_API_TOKEN");
        internalApiRejectedCounter("INVALID_API_TOKEN");
        managementRejectedCounter();
    }

    public void handled(@Nullable String eventType, Result result, Duration elapsed) {
        String type = normalize(eventType);
        switch (result) {
            case APPLIED -> counter("order.events.processed", "Events applied to the projection", type).increment();
            case DUPLICATE -> counter("order.events.duplicate", "Already processed events that were skipped", type).increment();
            case STALE -> counter("order.events.stale", "Events older than the applied state that were skipped", type).increment();
            case FAILED -> { }   // counted as retry or dead_lettered by the error handler
        }
        Timer.builder("order.events.processing")
                .description("Processing time of one delivery attempt (parse + projection transaction)")
                .tags("eventType", type, "result", result.name().toLowerCase())
                .register(registry)
                .record(elapsed);
    }

    public void retry(@Nullable String eventType) {
        counter("order.events.retry", "Failed delivery attempts with a transient failure", normalize(eventType)).increment();
    }

    public void deadLettered(@Nullable String eventType, boolean permanent) {
        deadLetteredCounter(normalize(eventType), permanent).increment();
    }

    private Counter deadLetteredCounter(String eventType, boolean permanent) {
        return Counter.builder("order.events.dead_lettered")
                .description("Events sent to the dead-letter topic")
                .tags("eventType", eventType, "reason", permanent ? "permanent" : "retries_exhausted")
                .register(registry);
    }

    public void internalApiRejected(String code) {
        internalApiRejectedCounter(code).increment();
    }

    public void managementRejected() {
        managementRejectedCounter().increment();
    }

    private Counter internalApiRejectedCounter(String code) {
        return Counter.builder("security.internal_api.rejected")
                .description("Internal API requests without a valid API token")
                .tag("reason", code)
                .register(registry);
    }

    private Counter managementRejectedCounter() {
        return Counter.builder("security.management.rejected")
                .description("Management endpoint requests without a valid management token")
                .register(registry);
    }

    private Counter counter(String name, String description, String eventType) {
        return Counter.builder(name).description(description).tag("eventType", eventType).register(registry);
    }

    static String normalize(@Nullable String eventType) {
        if (eventType == null) {
            return "unknown";
        }
        return KNOWN_EVENT_TYPES.contains(eventType) ? eventType : "other";
    }
}
