package pl.dch.marketplace.cart;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Quantity upper bound keeps requests sane (and far from int overflow when quantities are summed).
 */
record AddCartItemRequest(
        @NotNull Long productId,
        @NotNull @Min(1) @Max(1000) Integer quantity
) {
}
