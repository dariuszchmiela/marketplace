package pl.dch.orderactivity.event;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import pl.dch.orderactivity.event.PermanentEventException.MalformedEventException;
import pl.dch.orderactivity.event.PermanentEventException.UnsupportedEventException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.dch.orderactivity.TestEvents.orderCreated;
import static pl.dch.orderactivity.TestEvents.orderPaid;
import static pl.dch.orderactivity.TestEvents.orderPaymentFailed;
import static pl.dch.orderactivity.TestEvents.withSchemaVersion;

class OrderEventParserTest {

    private final OrderEventParser parser = new OrderEventParser(JsonMapper.builder().build());

    @Test
    void parsesTheProducerContract() {
        UUID eventId = UUID.randomUUID();

        OrderEvent event = parser.parse(orderCreated(eventId, 42, 1));

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.eventType()).isEqualTo("OrderCreated");
        assertThat(event.orderId()).isEqualTo(42);
        assertThat(event.sequence()).isEqualTo(1);
        assertThat(event.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(event.total()).isEqualByComparingTo("159.80");
        assertThat(event.currency()).isEqualTo("PLN");
    }

    @Test
    void readsTypeSpecificDetails() {
        assertThat(parser.parse(orderPaid(UUID.randomUUID(), 1, 2)).detail()).isEqualTo("pay-1");
        assertThat(parser.parse(orderPaymentFailed(UUID.randomUUID(), 1, 2)).detail()).isEqualTo("DECLINED");
    }

    @Test
    void ignoresUnknownFieldsAddedByTheProducer() {
        String withExtraField = orderCreated(UUID.randomUUID(), 5, 1).replace("\"sequence\"", "\"newField\": true, \"sequence\"");

        assertThat(parser.parse(withExtraField).orderId()).isEqualTo(5);
    }

    @Test
    void rejectsGarbageAsMalformed() {
        assertThatThrownBy(() -> parser.parse("definitely not json")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> parser.parse("[1,2]")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(MalformedEventException.class);
    }

    @Test
    void rejectsMissingOrInconsistentRequiredFields() {
        String event = orderCreated(UUID.randomUUID(), 5, 1);

        assertThatThrownBy(() -> parser.parse(event.replace("\"currency\": \"PLN\"", "\"currency\": \"zloty\"")))
                .isInstanceOf(MalformedEventException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> parser.parse(event.replace("\"aggregateId\": \"5\"", "\"aggregateId\": \"6\"")))
                .isInstanceOf(MalformedEventException.class).hasMessageContaining("aggregateId");
        assertThatThrownBy(() -> parser.parse(event.replace("\"total\": 159.80", "\"total\": -1")))
                .isInstanceOf(MalformedEventException.class).hasMessageContaining("total");
        assertThatThrownBy(() -> parser.parse(event.replace("\"eventId\": \"", "\"eventId\": \"x")))
                .isInstanceOf(MalformedEventException.class).hasMessageContaining("eventId");
    }

    @Test
    void rejectsUnsupportedSchemaVersionsAndTypes() {
        String event = orderCreated(UUID.randomUUID(), 5, 1);

        assertThatThrownBy(() -> parser.parse(withSchemaVersion(event, 2)))
                .isInstanceOf(UnsupportedEventException.class).hasMessageContaining("schemaVersion 2");
        assertThatThrownBy(() -> parser.parse(event.replace("OrderCreated", "OrderShipped")))
                .isInstanceOf(UnsupportedEventException.class);
    }
}
