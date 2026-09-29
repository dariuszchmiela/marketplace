package pl.dch.marketplace.checkout;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pl.dch.marketplace.order.Order;
import pl.dch.marketplace.order.OrderResponse;
import pl.dch.marketplace.payment.PaymentClient;
import pl.dch.marketplace.payment.PaymentOutcome;
import pl.dch.marketplace.payment.RemotePayment;
import pl.dch.marketplace.session.SessionId;

/**
 * Orchestrates checkout. Deliberately <strong>not</strong> {@code @Transactional}:
 * <ol>
 *   <li>transaction 1 ({@link OrderPlacementService}): order {@code PAYMENT_PENDING}, stock taken, cart cleared;
 *       committed;</li>
 *   <li>no transaction: payment-service call with timeouts, retry and circuit breaker ({@link PaymentClient});</li>
 *   <li>transaction 2 ({@link OrderPaymentUpdater}): payment outcome applied to the order.</li>
 * </ol>
 * No database connection is held while waiting for payment-service. If the process dies between the
 * steps, the order stays {@code PAYMENT_PENDING} and can be resolved by reconciliation.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final OrderPlacementService orderPlacement;
    private final PaymentClient paymentClient;
    private final OrderPaymentUpdater orderPaymentUpdater;

    public CheckoutService(OrderPlacementService orderPlacement,
                           PaymentClient paymentClient,
                           OrderPaymentUpdater orderPaymentUpdater) {
        this.orderPlacement = orderPlacement;
        this.paymentClient = paymentClient;
        this.orderPaymentUpdater = orderPaymentUpdater;
    }

    /**
     * Idempotent per {@code (session, checkoutIdempotencyKey)}: a repeated request returns the order
     * created by the first one, in its current state, and never calls payment-service again.
     *
     * @param paymentScenario dev/test failure scenario for payment-service, may be null
     */
    public CheckoutResult checkout(SessionId sessionId, UUID checkoutIdempotencyKey, @Nullable String paymentScenario) {
        OrderPlacementService.PlacedOrder placed;
        try {
            placed = orderPlacement.placeOrder(sessionId, checkoutIdempotencyKey);
        } catch (RuntimeException ex) {
            // A concurrent request with the same key may have committed first. Depending on timing our
            // transaction then fails on the unique constraint, on the stock version, or - if the winner
            // committed between our key lookup and our cart read - with CART_EMPTY / INSUFFICIENT_STOCK.
            // Whatever the cause: if an order exists for this key, it is the idempotent answer.
            // Otherwise the failure is real and is rethrown.
            return orderPlacement.findByCheckoutKey(sessionId, checkoutIdempotencyKey)
                    .map(order -> {
                        log.info("checkout.concurrent_duplicate orderId={} checkoutKey={}", order.id(), checkoutIdempotencyKey);
                        return CheckoutResult.replayed(order);
                    })
                    .orElseThrow(() -> ex);
        }
        if (!placed.created()) {
            log.info("checkout.replayed orderId={} status={} checkoutKey={}",
                    placed.order().id(), placed.order().status(), checkoutIdempotencyKey);
            return CheckoutResult.replayed(placed.order());
        }

        OrderResponse order = placed.order();
        log.info("checkout.order_placed orderId={} total={} checkoutKey={} paymentKey={}",
                order.id(), order.total(), checkoutIdempotencyKey, placed.paymentIdempotencyKey());

        // Transaction 1 is committed at this point.
        PaymentOutcome outcome = paymentClient.pay(
                new RemotePayment.Request(order.id(), order.total(), Order.CURRENCY, placed.paymentIdempotencyKey()),
                paymentScenario);

        return CheckoutResult.created(orderPaymentUpdater.applyOutcome(order.id(), outcome));
    }

    /**
     * @param created true for the request that created the order (201), false for a replay (200)
     */
    public record CheckoutResult(OrderResponse order, boolean created) {

        static CheckoutResult created(OrderResponse order) {
            return new CheckoutResult(order, true);
        }

        static CheckoutResult replayed(OrderResponse order) {
            return new CheckoutResult(order, false);
        }
    }
}
