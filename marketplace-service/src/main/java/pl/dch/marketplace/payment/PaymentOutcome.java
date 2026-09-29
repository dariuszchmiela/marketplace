package pl.dch.marketplace.payment;

/**
 * What the marketplace knows after calling payment-service. Transport details (status codes,
 * exceptions, retries) stay inside {@link PaymentClient}; callers only handle these four cases.
 */
public sealed interface PaymentOutcome {

    record Succeeded(String paymentId) implements PaymentOutcome {
    }

    /** A valid business result: the payment was processed and declined. */
    record Declined(String paymentId) implements PaymentOutcome {
    }

    /** payment-service provably did not process the payment, so nothing was charged. */
    record NotProcessed(String reason) implements PaymentOutcome {
    }

    /**
     * The request may have been processed but no result arrived (timeout, ambiguous 5xx).
     * Must not be treated as a failure: the payment may have succeeded.
     */
    record Unknown(String reason) implements PaymentOutcome {
    }
}
