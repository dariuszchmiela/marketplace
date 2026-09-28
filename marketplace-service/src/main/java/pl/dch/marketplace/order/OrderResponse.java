package pl.dch.marketplace.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        OrderStatus status,
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
        return new OrderResponse(order.getId(), order.getStatus(), order.getTotal(), order.getCreatedAt(), lines);
    }
}
