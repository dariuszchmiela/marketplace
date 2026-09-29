package pl.dch.orderactivity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import pl.dch.orderactivity.activity.OrderActivityRepository;
import pl.dch.orderactivity.simulation.FailureSimulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pl.dch.orderactivity.TestEvents.orderCreated;
import static pl.dch.orderactivity.TestEvents.orderPaid;
import static pl.dch.orderactivity.TestEvents.orderPaymentFailed;
import static pl.dch.orderactivity.TestEvents.withSchemaVersion;

/**
 * The consumer against real Kafka and PostgreSQL: events are produced to the topic exactly as the marketplace's
 * outbox publisher does (key = order id), and the test waits (bounded) for the projection to catch up.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderActivityConsumerIntegrationTest {

    private static final String TOPIC = "marketplace.order-events";
    private static final String DLT = "marketplace.order-events.DLT";
    private static final Duration CATCH_UP = Duration.ofSeconds(20);
    private static final AtomicLong ORDER_IDS = new AtomicLong(System.nanoTime() % 1_000_000 * 1000);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OrderActivityRepository activities;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FailureSimulator failureSimulator;

    @Autowired
    private KafkaConnectionDetails kafka;

    private KafkaConsumer<String, String> deadLetterReader;

    @BeforeEach
    void readDeadLetterTopicFromNow() {
        deadLetterReader = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafka.getBootstrapServers()),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        List<TopicPartition> partitions = deadLetterReader.partitionsFor(DLT, Duration.ofSeconds(10)).stream()
                .map(info -> new TopicPartition(DLT, info.partition())).toList();
        deadLetterReader.assign(partitions);
        deadLetterReader.seekToEnd(partitions);
        partitions.forEach(deadLetterReader::position);
    }

    @AfterEach
    void cleanUp() {
        deadLetterReader.close();
        failureSimulator.reset();
    }

    @Test
    void eventuallyBuildsTheProjectionFromOrderCreated() {
        long orderId = nextOrderId();
        UUID eventId = UUID.randomUUID();

        send(orderId, orderCreated(eventId, orderId, 1));

        // Asynchronous: the send returned long before the consumer committed; the view catches up later.
        await().atMost(CATCH_UP).untilAsserted(() -> assertThat(activities.find(orderId))
                .hasValueSatisfying(activity -> {
                    assertThat(activity.currentStatus()).isEqualTo("PAYMENT_PENDING");
                    assertThat(activity.total()).isEqualByComparingTo("159.80");
                    assertThat(activity.lastEventId()).isEqualTo(eventId);
                }));
        assertThat(activities.history(orderId)).extracting(OrderActivityRepository.Entry::message)
                .containsExactly("Order accepted, waiting for payment");
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void eventsOfOneOrderAreAppliedInPartitionOrder() {
        long orderId = nextOrderId();

        send(orderId, orderCreated(UUID.randomUUID(), orderId, 1));
        send(orderId, orderPaid(UUID.randomUUID(), orderId, 2));

        awaitStatus(orderId, "PAID");
        assertThat(activities.history(orderId)).extracting(OrderActivityRepository.Entry::eventType)
                .containsExactly("OrderCreated", "OrderPaid");
        assertThat(activities.history(orderId)).extracting(OrderActivityRepository.Entry::sequence).containsExactly(1, 2);
    }

    @Test
    void theSameEventDeliveredTwiceIsAppliedOnce() {
        long orderId = nextOrderId();
        UUID createdId = UUID.randomUUID();
        String created = orderCreated(createdId, orderId, 1);

        // The outbox crash window: the same logical event (same eventId) lands on the topic twice.
        send(orderId, created);
        send(orderId, created);
        send(orderId, orderPaid(UUID.randomUUID(), orderId, 2));   // same partition, so both copies are handled before it

        awaitStatus(orderId, "PAID");
        assertThat(activities.history(orderId)).extracting(OrderActivityRepository.Entry::eventType)
                .containsExactly("OrderCreated", "OrderPaid");
        assertThat(processed(createdId)).isEqualTo(1);
    }

    @Test
    void transientFailureIsRetriedAndTheFailedAttemptsLeaveNoTrace() {
        long orderId = nextOrderId();
        UUID eventId = UUID.randomUUID();
        // Fails after the projection and processed_event were written, before commit - twice.
        failureSimulator.failTransiently(Long.toString(orderId), 2);

        send(orderId, orderCreated(eventId, orderId, 1));

        awaitStatus(orderId, "PAYMENT_PENDING");
        assertThat(failureSimulator.attempts(Long.toString(orderId))).isEqualTo(3);
        // The two rolled-back attempts left nothing behind: one history entry, one processed_event row.
        assertThat(activities.history(orderId)).hasSize(1);
        assertThat(processed(eventId)).isEqualTo(1);
    }

    @Test
    void exhaustedRetriesGoToTheDeadLetterTopicAndLaterRecordsAreStillProcessed() {
        long failing = nextOrderId();
        long next = nextOrderId();
        UUID failingEventId = UUID.randomUUID();
        failureSimulator.failTransiently(Long.toString(failing), 100);

        send(failing, orderCreated(failingEventId, failing, 1));
        send(next, orderCreated(UUID.randomUUID(), next, 1));

        ConsumerRecord<String, String> dead = awaitDeadLetter(failingEventId);
        assertThat(dead.key()).isEqualTo(Long.toString(failing));
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
        assertThat(header(dead, "kafka_dlt-original-partition")).isNotNull();
        assertThat(header(dead, "kafka_dlt-original-offset")).isNotNull();
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("SimulatedTransientFailure");
        assertThat(header(dead, "kafka_dlt-exception-stacktrace")).isNull();
        assertThat(header(dead, "eventType")).isEqualTo("OrderCreated");
        // 1 delivery + 3 retries, then dead-lettered; nothing was committed for it.
        assertThat(failureSimulator.attempts(Long.toString(failing))).isEqualTo(4);
        assertThat(activities.find(failing)).isEmpty();
        assertThat(processed(failingEventId)).isZero();

        awaitStatus(next, "PAYMENT_PENDING");
    }

    @Test
    void malformedEventIsDeadLetteredWithoutRetries() {
        long orderId = nextOrderId();

        kafkaTemplate.send(new ProducerRecord<>(TOPIC, Long.toString(orderId), "{ this is not json"));

        ConsumerRecord<String, String> dead = awaitDeadLetter(record -> Long.toString(orderId).equals(record.key()));
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("MalformedEventException");
        assertThat(dead.value()).isEqualTo("{ this is not json");
    }

    @Test
    void unsupportedSchemaVersionIsDeadLettered() {
        long orderId = nextOrderId();
        UUID eventId = UUID.randomUUID();

        send(orderId, withSchemaVersion(orderCreated(eventId, orderId, 1), 2));

        assertThat(header(awaitDeadLetter(eventId), "kafka_dlt-exception-cause-fqcn")).contains("UnsupportedEventException");
        assertThat(activities.find(orderId)).isEmpty();
    }

    @Test
    void impossibleTransitionIsDeadLetteredAndTheProjectionKeepsItsState() {
        long orderId = nextOrderId();
        UUID bogus = UUID.randomUUID();

        send(orderId, orderCreated(UUID.randomUUID(), orderId, 1));
        send(orderId, orderPaid(UUID.randomUUID(), orderId, 2));
        send(orderId, orderPaymentFailed(bogus, orderId, 3));   // PAID is final: a producer bug or a manual injection

        assertThat(header(awaitDeadLetter(bogus), "kafka_dlt-exception-cause-fqcn")).contains("InvalidStateTransitionException");
        assertThat(activities.find(orderId)).hasValueSatisfying(activity -> assertThat(activity.currentStatus()).isEqualTo("PAID"));
        assertThat(processed(bogus)).isZero();
    }

    @Test
    void replayedOlderEventIsIgnored() {
        long orderId = nextOrderId();
        UUID replayedCreated = UUID.randomUUID();

        send(orderId, orderCreated(UUID.randomUUID(), orderId, 1));
        send(orderId, orderPaid(UUID.randomUUID(), orderId, 2));
        // Re-published by some tooling with a new eventId: not a duplicate by id, but stale by sequence.
        send(orderId, orderCreated(replayedCreated, orderId, 1));

        await().atMost(CATCH_UP).until(() -> processed(replayedCreated) == 1);
        assertThat(activities.find(orderId)).hasValueSatisfying(activity -> assertThat(activity.currentStatus()).isEqualTo("PAID"));
        assertThat(activities.history(orderId)).hasSize(2);
    }

    private void send(long orderId, String event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, Long.toString(orderId), event);
        record.headers().add("eventId", event.substring(event.indexOf("\"eventId\": \"") + 12, event.indexOf("\"eventId\": \"") + 48).getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", event.contains("\"eventType\": \"")
                ? event.substring(event.indexOf("\"eventType\": \"") + 14, event.indexOf('"', event.indexOf("\"eventType\": \"") + 14)).getBytes(StandardCharsets.UTF_8)
                : new byte[0]);
        kafkaTemplate.send(record).join();
    }

    private void awaitStatus(long orderId, String status) {
        await().atMost(CATCH_UP).untilAsserted(() -> assertThat(activities.find(orderId))
                .hasValueSatisfying(activity -> assertThat(activity.currentStatus()).isEqualTo(status)));
    }

    private ConsumerRecord<String, String> awaitDeadLetter(UUID eventId) {
        return awaitDeadLetter(record -> record.value().contains(eventId.toString()));
    }

    private ConsumerRecord<String, String> awaitDeadLetter(java.util.function.Predicate<ConsumerRecord<String, String>> filter) {
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        return await().atMost(CATCH_UP).until(() -> {
            deadLetterReader.poll(Duration.ofMillis(200)).forEach(seen::add);
            return seen.stream().filter(filter).findFirst().orElse(null);
        }, record -> record != null);
    }

    private int processed(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from processed_event where event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static long nextOrderId() {
        return ORDER_IDS.incrementAndGet();
    }
}
