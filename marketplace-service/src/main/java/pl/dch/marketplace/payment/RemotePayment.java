package pl.dch.marketplace.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * payment-service's API contract, kept separate from the marketplace domain.
 */
public final class RemotePayment {

    private RemotePayment() {
    }

    /** No card data: the marketplace only says what to charge for which order. */
    public record Request(long orderId, BigDecimal amount, String currency, UUID idempotencyKey) {
    }

    /** Tolerant reader: new fields added by payment-service do not break us. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(
            String paymentId,
            long orderId,
            Status status,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            Instant createdAt
    ) {
    }

    public enum Status {
        SUCCEEDED,
        DECLINED
    }
}
