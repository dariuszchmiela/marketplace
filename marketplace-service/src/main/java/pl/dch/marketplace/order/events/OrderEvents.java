package pl.dch.marketplace.order.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import pl.dch.marketplace.order.Order;
import pl.dch.marketplace.outbox.OutboxWriter;

/**
 * Order integration events. Called by the services in the same transaction as the state change they
 * describe; they only write outbox rows. {@link Order} itself knows nothing about events or Kafka.
 * <p>
 * Payloads are explicit records (never entities) and self-contained: a consumer can build its view from them
 * without calling the marketplace. The anonymous session id is <strong>not</strong> included: it is a bearer
 * identifier that grants access to the cart and orders.
 */
@Component
public class OrderEvents {

    public static final String AGGREGATE_TYPE = "Order";
    public static final int SCHEMA_VERSION = 1;

    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_PAID = "OrderPaid";
    public static final String ORDER_PAYMENT_FAILED = "OrderPaymentFailed";
    public static final String ORDER_PAYMENT_UNKNOWN = "OrderPaymentUnknown";

    public record Line(long productId, String productName, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
    }

    public record OrderCreated(long orderId, String status, BigDecimal total, String currency, Instant createdAt,
                               List<Line> lines) {
    }

    public record OrderPaid(long orderId, String status, BigDecimal total, String currency, String paymentId) {
    }

    /** reason: DECLINED or NOT_PROCESSED; paymentId only for DECLINED. Nothing was charged in both cases. */
    public record OrderPaymentFailed(long orderId, String status, BigDecimal total, String currency, String reason,
                                     String paymentId) {
    }

    /** Emitted so downstream views can show "being verified"; followed later by OrderPaid or OrderPaymentFailed. */
    public record OrderPaymentUnknown(long orderId, String status, BigDecimal total, String currency) {
    }

    private final OutboxWriter outbox;

    public OrderEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public UUID orderCreated(Order order) {
        List<Line> lines = order.getLines().stream()
                .map(line -> new Line(line.getProductId(), line.getProductName(), line.getUnitPrice(),
                        line.getQuantity(), line.getLineTotal()))
                .toList();
        return append(order, ORDER_CREATED, new OrderCreated(order.getId(), order.getStatus().name(), order.getTotal(),
                Order.CURRENCY, order.getCreatedAt(), lines));
    }

    public UUID orderPaid(Order order) {
        return append(order, ORDER_PAID, new OrderPaid(order.getId(), order.getStatus().name(), order.getTotal(),
                Order.CURRENCY, order.getPaymentId()));
    }

    public UUID orderPaymentFailed(Order order) {
        return append(order, ORDER_PAYMENT_FAILED, new OrderPaymentFailed(order.getId(), order.getStatus().name(),
                order.getTotal(), Order.CURRENCY, order.getPaymentFailureReason().name(), order.getPaymentId()));
    }

    public UUID orderPaymentUnknown(Order order) {
        return append(order, ORDER_PAYMENT_UNKNOWN, new OrderPaymentUnknown(order.getId(), order.getStatus().name(),
                order.getTotal(), Order.CURRENCY));
    }

    private UUID append(Order order, String eventType, Object payload) {
        return outbox.append(AGGREGATE_TYPE, order.getId().toString(), eventType, SCHEMA_VERSION, payload);
    }
}
