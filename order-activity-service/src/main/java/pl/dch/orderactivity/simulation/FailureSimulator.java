package pl.dch.orderactivity.simulation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.dch.orderactivity.event.OrderEvent;
import pl.dch.orderactivity.event.PermanentEventException;

/**
 * Deterministic failure injection for tests and local demos. Inert unless a rule was registered (by a test, or
 * through {@link SimulationController}, which exists only with {@code order-activity.simulation.enabled=true}).
 * A rule matches an event by order id or by event type.
 */
@Component
public class FailureSimulator {

    private static final Logger log = LoggerFactory.getLogger(FailureSimulator.class);

    /** Retryable: stands for a temporary problem such as a database hiccup. */
    public static class SimulatedTransientFailure extends RuntimeException {

        public SimulatedTransientFailure(String message) {
            super(message);
        }
    }

    public static class SimulatedPermanentFailure extends PermanentEventException {

        public SimulatedPermanentFailure(String message) {
            super(message);
        }
    }

    private final Map<String, AtomicInteger> transientFailures = new ConcurrentHashMap<>();
    private final Map<String, Boolean> permanentFailures = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    /** The next {@code times} attempts of matching events fail with a retryable exception. */
    public void failTransiently(String match, int times) {
        transientFailures.put(match, new AtomicInteger(times));
    }

    /** The next matching event fails permanently (not retried). */
    public void failPermanently(String match) {
        permanentFailures.put(match, true);
    }

    /** How often an event matching {@code match} reached the end of processing (successful or not). */
    public int attempts(String match) {
        return attempts.getOrDefault(match, new AtomicInteger()).get();
    }

    public void reset() {
        transientFailures.clear();
        permanentFailures.clear();
        attempts.clear();
    }

    public void beforeCommit(OrderEvent event) {
        for (String key : new String[] {Long.toString(event.orderId()), event.eventType()}) {
            attempts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
            if (permanentFailures.remove(key) != null) {
                log.warn("simulation.permanent_failure eventId={} match={}", event.eventId(), key);
                throw new SimulatedPermanentFailure("Simulated permanent failure for " + key);
            }
            AtomicInteger remaining = transientFailures.get(key);
            if (remaining != null && remaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                log.warn("simulation.transient_failure eventId={} match={} remaining={}", event.eventId(), key, remaining.get());
                throw new SimulatedTransientFailure("Simulated transient failure for " + key);
            }
        }
    }
}
