package pl.dch.orderactivity.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import pl.dch.orderactivity.event.PermanentEventException.MalformedEventException;
import pl.dch.orderactivity.event.PermanentEventException.UnsupportedEventException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and validates the Kafka value (JSON envelope). Validation failures are permanent: redelivering the same
 * bytes can never make them valid.
 */
@Component
public class OrderEventParser {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final Set<String> SUPPORTED_TYPES =
            Set.of("OrderCreated", "OrderPaid", "OrderPaymentFailed", "OrderPaymentUnknown");

    private final JsonMapper jsonMapper;

    public OrderEventParser(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public OrderEvent parse(String json) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(json == null ? "" : json);
        } catch (JacksonException ex) {
            throw new MalformedEventException("Event is not valid JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new MalformedEventException("Event is not a JSON object", null);
        }

        String eventType = text(root, "eventType");
        int schemaVersion = integer(root, "schemaVersion");
        if (!SUPPORTED_TYPES.contains(eventType)) {
            throw new UnsupportedEventException("Unsupported event type " + eventType);
        }
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new UnsupportedEventException("Unsupported schemaVersion " + schemaVersion + " of " + eventType);
        }
        if (!"Order".equals(text(root, "aggregateType"))) {
            throw new MalformedEventException("aggregateType must be Order", null);
        }

        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new MalformedEventException("payload is missing", null);
        }
        long orderId = payload.hasNonNull("orderId") && payload.get("orderId").canConvertToLong()
                ? payload.get("orderId").asLong() : invalid("payload.orderId");
        if (!Long.toString(orderId).equals(text(root, "aggregateId"))) {
            throw new MalformedEventException("aggregateId does not match payload.orderId", null);
        }
        int sequence = integer(root, "sequence");
        if (sequence < 1) {
            invalid("sequence");
        }
        BigDecimal total = payload.hasNonNull("total") && payload.get("total").isNumber()
                ? payload.get("total").decimalValue() : invalid("payload.total");
        if (total.signum() < 0) {
            invalid("payload.total");
        }
        String currency = text(payload, "currency");
        if (!currency.matches("[A-Z]{3}")) {
            invalid("payload.currency");
        }
        String detail = switch (eventType) {
            case "OrderPaid" -> optionalText(payload, "paymentId");
            case "OrderPaymentFailed" -> text(payload, "reason");
            default -> null;
        };

        return new OrderEvent(uuid(root, "eventId"), eventType, schemaVersion, orderId, sequence,
                instant(root, "occurredAt"), text(payload, "status"), total, currency, detail);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            return invalid(field);
        }
        return value.asString();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            return invalid(field);
        }
        return value.asInt();
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException ex) {
            return invalid(field);
        }
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException ex) {
            return invalid(field);
        }
    }

    private static <T> T invalid(String field) {
        throw new MalformedEventException("Missing or invalid field " + field, null);
    }
}
