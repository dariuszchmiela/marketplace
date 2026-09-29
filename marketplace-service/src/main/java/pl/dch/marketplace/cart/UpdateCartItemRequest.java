package pl.dch.marketplace.cart;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Sets the absolute quantity of a cart line. Use DELETE to remove a line (quantity 0 is rejected).
 */
record UpdateCartItemRequest(
        @NotNull @Min(1) @Max(CartItem.MAX_QUANTITY) Integer quantity
) {
}
