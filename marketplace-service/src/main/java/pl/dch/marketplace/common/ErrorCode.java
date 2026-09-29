package pl.dch.marketplace.common;

/**
 * Stable, machine-readable error codes returned in {@link ApiError#code()}.
 * The frontend can rely on these instead of parsing messages.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    MALFORMED_REQUEST,
    MISSING_SESSION_ID,
    INVALID_SESSION_ID,
    MISSING_IDEMPOTENCY_KEY,
    INVALID_IDEMPOTENCY_KEY,
    INVALID_QUANTITY,
    PRODUCT_NOT_FOUND,
    CART_ITEM_NOT_FOUND,
    ORDER_NOT_FOUND,
    CART_EMPTY,
    PRODUCT_UNAVAILABLE,
    INSUFFICIENT_STOCK,
    CONCURRENT_STOCK_CHANGE,
    CONCURRENT_MODIFICATION,
    PAYMENT_SERVICE_UNAVAILABLE,
    PAYMENT_RECONCILIATION_CONFLICT,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    REQUEST_FAILED,
    INTERNAL_ERROR
}
