package pl.dch.marketplace.order;

/**
 * Order lifecycle around the external payment.
 *
 * <pre>
 * PAYMENT_PENDING ──► PAID
 *        │  │
 *        │  └──────► PAYMENT_FAILED   (declined, or payment-service provably did not process it)
 *        ▼                ▲
 * PAYMENT_UNKNOWN ────────┘ and ──► PAID   (resolved by reconciliation)
 * </pre>
 *
 * {@link #PAID} and {@link #PAYMENT_FAILED} are final. Transitions are enforced by {@link Order}.
 */
public enum OrderStatus {

    /**
     * Legacy: orders created in Phase 1, before payment existed. New orders never get this status
     * and it has no transitions.
     */
    NEW,

    /** Order stored, stock taken, payment-service is being called. */
    PAYMENT_PENDING,

    PAID,

    /** Nothing was charged. The order's stock was returned and its items were put back into the cart. */
    PAYMENT_FAILED,

    /**
     * The marketplace does not know whether the payment happened (timeout, ambiguous 5xx).
     * Stock stays taken; reconciliation with payment-service resolves it.
     */
    PAYMENT_UNKNOWN;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PAYMENT_PENDING -> target == PAID || target == PAYMENT_FAILED || target == PAYMENT_UNKNOWN;
            case PAYMENT_UNKNOWN -> target == PAID || target == PAYMENT_FAILED;
            case NEW, PAID, PAYMENT_FAILED -> false;
        };
    }

    /** The payment result is not final yet, so it can still be applied or reconciled. */
    public boolean isAwaitingPaymentResult() {
        return this == PAYMENT_PENDING || this == PAYMENT_UNKNOWN;
    }
}
