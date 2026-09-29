package pl.dch.marketplace.outbox;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.dch.marketplace.order.events.OrderEvents;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure mapping: the JSON message that ends up in the outbox (and unchanged on Kafka). */
class OutboxWriterTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final OutboxWriter writer = new OutboxWriter(repository, jsonMapper, OutboxTracing.noop());

    @Test
    void writesAStableEnvelopeWithTheTypedPayload() {
        when(repository.nextSequence("Order", "42")).thenReturn(2);
        var payload = new OrderEvents.OrderPaid(42, "PAID", new BigDecimal("129.50"), "PLN", "pay-1");

        UUID eventId = writer.append("Order", "42", OrderEvents.ORDER_PAID, 1, payload);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(eq(eventId), eq("Order"), eq("42"), eq("OrderPaid"), eq(1), eq(2), json.capture(), any(Instant.class), isNull());
        JsonNode message = jsonMapper.readTree(json.getValue());
        assertThat(message.get("eventId").asString()).isEqualTo(eventId.toString());
        assertThat(message.get("eventType").asString()).isEqualTo("OrderPaid");
        assertThat(message.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(message.get("aggregateType").asString()).isEqualTo("Order");
        assertThat(message.get("aggregateId").asString()).isEqualTo("42");
        assertThat(message.get("sequence").asInt()).isEqualTo(2);
        assertThat(Instant.parse(message.get("occurredAt").asString())).isNotNull();
        assertThat(message.get("payload").get("orderId").asLong()).isEqualTo(42);
        assertThat(message.get("payload").get("total").decimalValue()).isEqualByComparingTo("129.50");
        assertThat(message.get("payload").get("paymentId").asString()).isEqualTo("pay-1");
    }

    @Test
    void orderCreatedPayloadRoundTrips() {
        when(repository.nextSequence(any(), any())).thenReturn(1);
        var created = new OrderEvents.OrderCreated(7, "PAYMENT_PENDING", new BigDecimal("20.00"), "PLN",
                Instant.parse("2026-09-29T10:00:00Z"),
                List.of(new OrderEvents.Line(3, "Lamp", new BigDecimal("10.00"), 2, new BigDecimal("20.00"))));

        writer.append("Order", "7", OrderEvents.ORDER_CREATED, 1, created);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(any(), any(), any(), any(), anyInt(), anyInt(), json.capture(), any(), any());
        OrderEvents.OrderCreated back = jsonMapper.treeToValue(jsonMapper.readTree(json.getValue()).get("payload"),
                OrderEvents.OrderCreated.class);
        // JSON numbers carry no scale (20.00 may come back as 20.0): money is compared by value, not equals().
        assertThat(back).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(created);
    }

    @Test
    void publisherBackoffDoublesAndIsCapped() {
        OutboxPublisher publisher = new OutboxPublisher(repository, record -> { }, null,
                new OutboxProperties.Publisher(false, Duration.ofSeconds(1), 10, Duration.ofSeconds(5),
                        Duration.ofSeconds(1), Duration.ofSeconds(60)), null, OutboxTracing.noop());

        assertThat(publisher.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(publisher.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(publisher.backoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(publisher.backoff(30)).isEqualTo(Duration.ofSeconds(60));
    }
}
