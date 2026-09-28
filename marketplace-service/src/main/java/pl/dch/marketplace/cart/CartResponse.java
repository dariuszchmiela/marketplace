package pl.dch.marketplace.cart;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cart view enriched with current product data. Prices and total are a preview only:
 * checkout recalculates them from the database.
 */
public record CartResponse(List<CartItemResponse> items, BigDecimal total) {

    /**
     * @param productExists false when the product was removed from the catalog after it was added;
     *                      name, price and line total are then {@code null}
     */
    public record CartItemResponse(
            Long productId,
            boolean productExists,
            String productName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal,
            int availableQuantity
    ) {
    }
}
