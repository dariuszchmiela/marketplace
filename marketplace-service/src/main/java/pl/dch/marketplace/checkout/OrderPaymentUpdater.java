package pl.dch.marketplace.checkout;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.dch.marketplace.cart.Cart;
import pl.dch.marketplace.cart.CartRepository;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.order.Order;
import pl.dch.marketplace.order.OrderLine;
import pl.dch.marketplace.order.OrderRepository;
import pl.dch.marketplace.order.OrderResponse;
import pl.dch.marketplace.order.OrderStatus;
import pl.dch.marketplace.order.events.OrderEvents;
import pl.dch.marketplace.payment.PaymentOutcome;
import pl.dch.marketplace.product.ProductRepository;
import pl.dch.marketplace.session.SessionId;

/**
 * Checkout transaction 2 (and the write side of reconciliation): applies a payment outcome to an
 * order in one short transaction.
 * <ul>
 *   <li>succeeded → {@code PAID}; the stock stays taken, the cart stays empty;</li>
 *   <li>declined / not processed → {@code PAYMENT_FAILED}; stock is returned and the items are put
 *       back into the session's cart, so nothing is silently lost;</li>
 *   <li>unknown → {@code PAYMENT_UNKNOWN}; stock stays taken until reconciliation decides.</li>
 * </ul>
 */
@Service
public class OrderPaymentUpdater {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentUpdater.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CartRepository cartRepository;
    private final OrderEvents orderEvents;

    public OrderPaymentUpdater(OrderRepository orderRepository,
                               ProductRepository productRepository,
                               CartRepository cartRepository,
                               OrderEvents orderEvents) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.cartRepository = cartRepository;
        this.orderEvents = orderEvents;
    }

    /**
     * Idempotent and safe against a concurrent checkout/reconciliation on the same order: the order row
     * is locked, and an order whose payment result is already final is returned unchanged.
     */
    @Transactional
    public OrderResponse applyOutcome(long orderId, PaymentOutcome outcome) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Order %d does not exist".formatted(orderId)));
        if (!order.isAwaitingPaymentResult()) {
            log.info("order.payment_outcome_ignored orderId={} status={} outcome={}", orderId, order.getStatus(), outcome);
            return OrderResponse.from(order);
        }

        OrderStatus before = order.getStatus();
        switch (outcome) {
            // Every real transition writes exactly one outbox event in this transaction. An outcome for a final
            // order returned above (replay, late reconciliation) writes nothing.
            case PaymentOutcome.Succeeded succeeded -> {
                order.markPaid(succeeded.paymentId());
                orderEvents.orderPaid(order);
            }
            case PaymentOutcome.Declined declined -> {
                order.markPaymentDeclined(declined.paymentId());
                returnItems(order);
                orderEvents.orderPaymentFailed(order);
            }
            case PaymentOutcome.NotProcessed ignored -> {
                order.markPaymentNotProcessed();
                returnItems(order);
                orderEvents.orderPaymentFailed(order);
            }
            case PaymentOutcome.Unknown ignored -> {
                // Already UNKNOWN (e.g. reconciliation did not find a payment): nothing new is known, no event.
                if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
                    order.markPaymentUnknown();
                    orderEvents.orderPaymentUnknown(order);
                }
            }
        }
        log.info("order.payment_status orderId={} from={} to={} outcome={}", orderId, before, order.getStatus(), outcome);
        return OrderResponse.from(order);
    }

    /** Read side for reconciliation: the order (scoped to its session) and its payment key. */
    @Transactional(readOnly = true)
    public PaymentTarget findPaymentTarget(SessionId sessionId, long orderId) {
        return orderRepository.findByIdAndSessionId(orderId, sessionId.value())
                .map(order -> new PaymentTarget(OrderResponse.from(order), order.getPaymentIdempotencyKey()))
                .orElseThrow(() -> new MarketplaceException(ErrorCode.ORDER_NOT_FOUND,
                        "Order %d not found".formatted(orderId)));
    }

    /**
     * Compensation. Runs at most once per order: only for an order that was still awaiting its result,
     * under the order row lock, in the same transaction as the transition to {@code PAYMENT_FAILED}.
     * Locks in the global order (order → cart → products by ascending id) so it cannot deadlock with
     * a checkout or a cart edit.
     */
    private void returnItems(Order order) {
        Cart cart = cartRepository.lockOrCreate(order.getSessionId());
        List<OrderLine> linesByProduct = order.getLines().stream()
                .sorted(Comparator.comparing(OrderLine::getProductId))
                .toList();
        for (OrderLine line : linesByProduct) {
            productRepository.increaseStock(line.getProductId(), line.getQuantity());
            cart.restoreItem(line.getProductId(), line.getQuantity());
        }
    }

    public record PaymentTarget(OrderResponse order, UUID paymentIdempotencyKey) {
    }
}
