package pl.dch.orderactivity;

import java.util.UUID;

/**
 * Builds order events in the producer's JSON format (the contract, written out by hand on purpose: the consumer
 * must work with the bytes on the topic, not with the producer's classes).
 */
public final class TestEvents {

    private TestEvents() {
    }

    public static String orderCreated(UUID eventId, long orderId, int sequence) {
        return envelope(eventId, "OrderCreated", 1, orderId, sequence, """
                {"orderId": %d, "status": "PAYMENT_PENDING", "total": 159.80, "currency": "PLN",
                 "createdAt": "2026-09-29T10:00:00Z",
                 "lines": [{"productId": 7, "productName": "Lamp", "unitPrice": 79.90, "quantity": 2, "lineTotal": 159.80}]}"""
                .formatted(orderId));
    }

    public static String orderPaid(UUID eventId, long orderId, int sequence) {
        return envelope(eventId, "OrderPaid", 1, orderId, sequence, """
                {"orderId": %d, "status": "PAID", "total": 159.80, "currency": "PLN", "paymentId": "pay-1"}""".formatted(orderId));
    }

    public static String orderPaymentFailed(UUID eventId, long orderId, int sequence) {
        return envelope(eventId, "OrderPaymentFailed", 1, orderId, sequence, """
                {"orderId": %d, "status": "PAYMENT_FAILED", "total": 159.80, "currency": "PLN", "reason": "DECLINED", "paymentId": "pay-2"}"""
                .formatted(orderId));
    }

    public static String withSchemaVersion(String event, int version) {
        return event.replace("\"schemaVersion\": 1", "\"schemaVersion\": " + version);
    }

    private static String envelope(UUID eventId, String type, int schemaVersion, long orderId, int sequence, String payload) {
        return """
                {"eventId": "%s", "eventType": "%s", "schemaVersion": %d, "aggregateType": "Order", "aggregateId": "%d",
                 "sequence": %d, "occurredAt": "2026-09-29T10:00:0%dZ", "payload": %s}"""
                .formatted(eventId, type, schemaVersion, orderId, sequence, Math.min(sequence, 9), payload);
    }
}
