package pl.dch.marketplace.outbox;

import java.time.Duration;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.test.context.TestPropertySource;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The real, scheduled publisher (own Spring context with polling on). Checkout returns as soon as the database
 * committed; the events reach Kafka a little later on their own — eventual consistency on the producer side.
 */
@TestPropertySource(properties = {"outbox.publisher.enabled=true", "outbox.publisher.poll-interval=200ms"})
class OutboxSchedulingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private KafkaConnectionDetails kafka;

    @Test
    void scheduledPublisherEventuallyPublishesTheEventsOfACommittedCheckout() throws Exception {
        Product lamp = createProduct("Scheduled Lamp", "10.00", 5);
        addToCart(session, lamp.getId(), 1);
        try (TopicReader reader = new TopicReader(String.join(",", kafka.getBootstrapServers()), "marketplace.order-events")) {

            String body = mockMvc.perform(checkout()).andReturn().getResponse().getContentAsString();
            String orderId = Long.toString(((Number) JsonPath.read(body, "$.id")).longValue());
            // The HTTP response is already final; it never waited for Kafka.
            assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("PAID");

            List<ConsumerRecord<String, String>> records = reader.await(record -> record.key().equals(orderId), 2, Duration.ofSeconds(15));
            assertThat(records).extracting(record -> TopicReader.header(record, "eventType")).containsExactly("OrderCreated", "OrderPaid");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from outbox_event where aggregate_id = ? and published_at is null", Integer.class, orderId))
                    .isZero());
        }
    }
}
