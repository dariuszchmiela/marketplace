package pl.dch.marketplace.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code paymentId} is set once payment-service returned a payment; {@code paymentFailureReason}
 * only for {@link OrderStatus#PAYMENT_FAILED}. Idempotency keys are internal and not exposed.
 */
public record OrderResponse(
        Long id,
        OrderStatus status,
        PaymentFailureReason paymentFailureReason,
        String paymentId,
        BigDecimal total,
        Instant createdAt,
        List<OrderLineResponse> lines
) {

    public record OrderLineResponse(
            Long productId,
            String productName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal
    ) {
    }

    public static OrderResponse from(Order order) {
        List<OrderLineResponse> lines = order.getLines().stream()
                .map(line -> new OrderLineResponse(
                        line.getProductId(),
                        line.getProductName(),
                        line.getUnitPrice(),
                        line.getQuantity(),
                        line.getLineTotal()))
                .toList();
        return new OrderResponse(order.getId(), order.getStatus(), order.getPaymentFailureReason(),
                order.getPaymentId(), order.getTotal(), order.getCreatedAt(), lines);
    }
}
