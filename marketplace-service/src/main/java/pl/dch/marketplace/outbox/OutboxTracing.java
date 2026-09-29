package pl.dch.marketplace.outbox;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.jspecify.annotations.Nullable;

/**
 * Carries the trace across the asynchronous gap of the outbox:
 * <pre>
 * HTTP request (trace T) ── transaction: order + outbox row { trace_parent = "00-T-spanX-01" }
 *                           ... the row waits: milliseconds normally, minutes during a Kafka outage ...
 * publisher poll ── "outbox.publish" span, parent = spanX (restored from trace_parent)       ← same trace T
 *                     └─ KafkaTemplate send span (Spring Kafka observation), W3C header "traceparent" in the record
 *                          └─ order-activity-service consumer span (Spring Kafka observation)
 * </pre>
 * Why store the context instead of starting a fresh trace at publish time: the publisher runs on a scheduler thread,
 * where the originating request's context no longer exists. Reading it back from the row is real continuity (the
 * parent span id is exactly the span that wrote the row), not an imitation. The time gap is honest too: the trace shows
 * the publish span starting late, which is precisely the outbox lag.
 * <p>
 * The outbox row is treated like a message on a queue: the publisher is its receiver (Micrometer
 * {@link ReceiverContext}: the tracing handler extracts the parent from the "carrier", i.e. the row) and then the
 * producer to Kafka. Without a stored context (tracing off, rows from before Phase 6) the publish span starts a new
 * trace. Only the traceparent is stored: no tracestate/baggage, no business data.
 */
public class OutboxTracing {

    static final String TRACEPARENT = "traceparent";
    static final String OBSERVATION = "outbox.publish";

    private final Tracer tracer;
    private final Propagator propagator;
    private final ObservationRegistry observationRegistry;

    public OutboxTracing(Tracer tracer, Propagator propagator, ObservationRegistry observationRegistry) {
        this.tracer = tracer;
        this.propagator = propagator;
        this.observationRegistry = observationRegistry;
    }

    public static OutboxTracing noop() {
        return new OutboxTracing(Tracer.NOOP, Propagator.NOOP, ObservationRegistry.NOOP);
    }

    /** The W3C traceparent of the current span, e.g. "00-{traceId}-{spanId}-01"; null without an active trace. */
    public @Nullable String currentTraceParent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /**
     * Runs the Kafka send of one row inside an {@code outbox.publish} observation: a span continuing the row's trace and
     * a timer {@code outbox.publish{event_type, error}} = time until the broker acknowledged the record.
     */
    public void observePublish(OutboxRecord record, Runnable send) {
        ReceiverContext<OutboxRecord> context = new ReceiverContext<>(
                (row, key) -> TRACEPARENT.equals(key) ? row.traceParent() : null);
        context.setCarrier(record);
        context.setRemoteServiceName("outbox");
        Observation.createNotStarted(OBSERVATION, () -> context, observationRegistry)
                .contextualName("outbox publish " + record.eventType())
                .lowCardinalityKeyValue("event_type", record.eventType())
                .highCardinalityKeyValue("outbox.attempt", Integer.toString(record.attemptCount() + 1))
                .observe(send);
    }
}
