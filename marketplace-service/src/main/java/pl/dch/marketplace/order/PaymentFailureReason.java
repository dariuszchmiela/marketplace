package pl.dch.marketplace.order;

/**
 * Why an order is {@link OrderStatus#PAYMENT_FAILED}. In both cases nothing was charged.
 */
public enum PaymentFailureReason {

    /** payment-service processed the payment and the card network declined it (business result). */
    DECLINED,

    /**
     * payment-service provably did not process the payment: it was unreachable, the circuit breaker
     * was open, it answered 503 (its contract: nothing recorded) or rejected the request as invalid.
     */
    NOT_PROCESSED
}
