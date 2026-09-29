package pl.dch.orderactivity.activity;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.dch.orderactivity.event.OrderEvent;
import pl.dch.orderactivity.event.PermanentEventException.InvalidStateTransitionException;
import pl.dch.orderactivity.simulation.FailureSimulator;

/**
 * Applies one order event to the projection, in <strong>one local transaction</strong>:
 * <pre>
 * INSERT processed_event (event_id)  -- ON CONFLICT DO NOTHING: 0 rows = duplicate -> done, nothing else happens
 * SELECT order_activity FOR UPDATE
 * stale (sequence &lt;= last applied)? -> ignore
 * invalid transition?               -> permanent failure (dead-letter topic)
 * INSERT/UPDATE order_activity + INSERT order_activity_entry
 * COMMIT                            -- only then does the listener return and the offset get committed
 * </pre>
 * If anything fails before the commit, everything rolls back, including the processed_event row, and Kafka
 * redelivers the event. Kafka offsets alone cannot provide this: an offset commit can be lost after the DB commit
 * (rebalance, crash), and the outbox itself may publish an event twice.
 */
@Service
public class OrderActivityProjector {

    private static final Logger log = LoggerFactory.getLogger(OrderActivityProjector.class);

    /** Same lifecycle as the marketplace order; the consumer validates it instead of trusting partition order blindly. */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "PAYMENT_PENDING", Set.of("PAID", "PAYMENT_FAILED", "PAYMENT_UNKNOWN"),
            "PAYMENT_UNKNOWN", Set.of("PAID", "PAYMENT_FAILED"),
            "PAID", Set.of(),
            "PAYMENT_FAILED", Set.of());

    public enum Result { APPLIED, DUPLICATE, STALE }

    private final ProcessedEventRepository processedEvents;
    private final OrderActivityRepository activities;
    private final FailureSimulator failureSimulator;

    public OrderActivityProjector(ProcessedEventRepository processedEvents, OrderActivityRepository activities,
                                  FailureSimulator failureSimulator) {
        this.processedEvents = processedEvents;
        this.activities = activities;
        this.failureSimulator = failureSimulator;
    }

    @Transactional
    public Result apply(OrderEvent event) {
        if (!processedEvents.markProcessed(event)) {
            log.info("event.duplicate eventId={} eventType={} orderId={}", event.eventId(), event.eventType(), event.orderId());
            return Result.DUPLICATE;
        }

        Optional<OrderActivityRepository.Activity> current = activities.findForUpdate(event.orderId());
        if (current.isEmpty()) {
            // Normally OrderCreated. Events are self-contained, so a later event can also start the view.
            activities.insert(event);
        } else {
            OrderActivityRepository.Activity activity = current.get();
            if (event.sequence() <= activity.lastSequence()) {
                // Replayed/older event (e.g. re-published by an operator): newer state is already applied.
                log.info("event.stale eventId={} eventType={} orderId={} sequence={} lastSequence={}",
                        event.eventId(), event.eventType(), event.orderId(), event.sequence(), activity.lastSequence());
                return Result.STALE;
            }
            if (!ALLOWED_TRANSITIONS.getOrDefault(activity.currentStatus(), Set.of()).contains(event.status())) {
                throw new InvalidStateTransitionException("Order %d cannot go from %s to %s (event %s)"
                        .formatted(event.orderId(), activity.currentStatus(), event.status(), event.eventId()));
            }
            activities.update(event);
        }
        activities.addEntry(event, message(event));

        // Dev/test hook (inert unless a failure was registered): fails AFTER all writes, before the commit,
        // to prove that nothing - including processed_event - survives a failed attempt.
        failureSimulator.beforeCommit(event);

        log.info("event.processed eventId={} eventType={} orderId={} sequence={} status={}",
                event.eventId(), event.eventType(), event.orderId(), event.sequence(), event.status());
        return Result.APPLIED;
    }

    private static String message(OrderEvent event) {
        return switch (event.eventType()) {
            case "OrderCreated" -> "Order accepted, waiting for payment";
            case "OrderPaid" -> "Payment confirmed";
            case "OrderPaymentFailed" -> "Payment failed (" + event.detail() + "), nothing was charged";
            case "OrderPaymentUnknown" -> "Payment status is being verified";
            default -> event.eventType();
        };
    }
}
