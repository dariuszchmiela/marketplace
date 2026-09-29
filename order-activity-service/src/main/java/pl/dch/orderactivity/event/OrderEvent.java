package pl.dch.orderactivity.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An order event as this consumer understands it: the envelope plus the fields every order event payload
 * carries. This is the consumer's own copy of the contract (no shared library with the producer); unknown
 * JSON fields are ignored (tolerant reader), unknown schema versions are rejected.
 *
 * @param detail reason for OrderPaymentFailed, payment id for OrderPaid, otherwise null
 */
public record OrderEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        long orderId,
        int sequence,
        Instant occurredAt,
        String status,
        BigDecimal total,
        String currency,
        String detail
) {
}
