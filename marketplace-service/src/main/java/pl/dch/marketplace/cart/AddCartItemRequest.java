package pl.dch.marketplace.cart;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The upper bound is the domain limit {@link CartItem#MAX_QUANTITY}, checked here for early feedback.
 * The domain still enforces it for the resulting line quantity (repeated additions).
 */
record AddCartItemRequest(
        @NotNull Long productId,
        @NotNull @Min(1) @Max(CartItem.MAX_QUANTITY) Integer quantity
) {
}
