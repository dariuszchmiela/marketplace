package pl.dch.marketplace.outbox;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One trace across every hop of a checkout:
 * <pre>
 * client (traceparent) → marketplace HTTP → payment-service HTTP call (traceparent header)
 *                                        → outbox row (trace_parent column) ... later ... publisher → Kafka record header
 * </pre>
 */
@ExtendWith(OutputCaptureExtension.class)
class TracePropagationIntegrationTest extends IntegrationTestBase {

    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");
    private static final String INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private KafkaConnectionDetails kafka;

    @Test
    void theClientsTraceContinuesIntoPaymentServiceTheOutboxAndKafka(CapturedOutput output) throws Exception {
        Product lamp = createProduct("Traced Lamp", "10.00", 10);
        addToCart(user, lamp.getId(), 1);

        String body;
        try (TopicReader reader = new TopicReader(String.join(",", kafka.getBootstrapServers()), "marketplace.order-events")) {
            // A browser or an edge proxy started the trace; the marketplace continues it.
            body = mockMvc.perform(checkout().header("traceparent", "00-" + INCOMING_TRACE_ID + "-00f067aa0ba902b7-01"))
                    .andReturn().getResponse().getContentAsString();
            long orderId = JsonPath.<Number>read(body, "$.id").longValue();

            // 1. payment-service received the same trace id in the W3C header of the HTTP call.
            String sentTraceparent = PAYMENT_SERVICE.postRequests().getLast().header("traceparent");
            assertThat(traceIdOf(sentTraceparent)).isEqualTo(INCOMING_TRACE_ID);

            // 2. Both outbox rows of the order (created, paid) remember the trace of the transaction that wrote them.
            List<String> stored = jdbcTemplate.queryForList(
                    "select trace_parent from outbox_event where aggregate_id = ? order by sequence", String.class,
                    Long.toString(orderId));
            assertThat(stored).hasSize(2).allSatisfy(traceparent ->
                    assertThat(traceIdOf(traceparent)).isEqualTo(INCOMING_TRACE_ID));

            // 3. After the asynchronous gap, the publisher restores that context: the Kafka records carry the same trace
            //    in their traceparent header (a new span id: the producer span, child of the restored outbox span).
            publisher.publishAllPending(10);
            List<ConsumerRecord<String, String>> records = reader.await(
                    record -> record.key().equals(Long.toString(orderId)), 2, Duration.ofSeconds(20));
            assertThat(records).allSatisfy(record -> {
                String header = TopicReader.header(record, "traceparent");
                assertThat(traceIdOf(header)).isEqualTo(INCOMING_TRACE_ID);
                assertThat(stored).doesNotContain(header);
            });
        }

        // 4. Log correlation: the checkout's log lines carry the trace id in the MDC.
        assertThat(output.getOut().lines().filter(line -> line.contains("checkout.order_placed")))
                .anySatisfy(line -> assertThat(line).contains("traceId=" + INCOMING_TRACE_ID));
    }

    @Test
    void withoutAnIncomingTraceTheServerStartsOneAndStillPropagatesIt() throws Exception {
        Product lamp = createProduct("Untraced Lamp", "10.00", 10);
        addToCart(user, lamp.getId(), 1);

        String body = mockMvc.perform(checkout()).andReturn().getResponse().getContentAsString();
        long orderId = JsonPath.<Number>read(body, "$.id").longValue();

        String traceId = traceIdOf(PAYMENT_SERVICE.postRequests().getLast().header("traceparent"));
        String stored = jdbcTemplate.queryForObject("select trace_parent from outbox_event where aggregate_id = ? and sequence = 1",
                String.class, Long.toString(orderId));
        assertThat(traceIdOf(stored)).isEqualTo(traceId);
    }

    private static String traceIdOf(String traceparent) {
        assertThat(traceparent).isNotNull();
        Matcher matcher = TRACEPARENT.matcher(traceparent);
        assertThat(matcher.matches()).as("W3C traceparent: " + traceparent).isTrue();
        return matcher.group(1);
    }
}
