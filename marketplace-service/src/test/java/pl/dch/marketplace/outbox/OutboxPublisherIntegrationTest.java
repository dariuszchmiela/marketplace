package pl.dch.marketplace.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Outbox → Kafka against a real broker (Testcontainers). Scheduled polling is off; each test drives the
 * publisher explicitly, including broken publishers built from the same parts.
 */
class OutboxPublisherIntegrationTest extends IntegrationTestBase {

    private static final String TOPIC = "marketplace.order-events";

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private EventSender kafkaSender;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaConnectionDetails kafka;

    @BeforeEach
    void forgetRowsOfOtherTests() {
        // Other test classes leave pending rows behind (their publisher never ran). Mark them as handled so
        // that every test here only claims its own rows.
        jdbcTemplate.update("update outbox_event set published_at = now() where published_at is null");
    }

    @Test
    void publishesPendingEventsKeyedByOrderIdInOrderWithHeaders() throws Exception {
        long orderId = paidOrder();
        try (TopicReader reader = reader()) {
            // Head of line: only OrderCreated is eligible; OrderPaid of the same order waits for the next batch.
            assertThat(publisher.publishPendingBatch()).isEqualTo(new OutboxPublisher.BatchResult(1, 1, 0));
            assertThat(publisher.publishPendingBatch()).isEqualTo(new OutboxPublisher.BatchResult(1, 1, 0));
            assertThat(publisher.publishPendingBatch().claimed()).isZero();

            List<ConsumerRecord<String, String>> records = reader.await(record -> record.key().equals(Long.toString(orderId)), 2, Duration.ofSeconds(10));

            assertThat(records).extracting(record -> TopicReader.header(record, "eventType")).containsExactly("OrderCreated", "OrderPaid");
            assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());
            assertThat(records.get(1).offset()).isGreaterThan(records.get(0).offset());
            for (ConsumerRecord<String, String> record : records) {
                assertThat(JsonPath.<String>read(record.value(), "$.eventId")).isEqualTo(TopicReader.header(record, "eventId"));
                assertThat(JsonPath.<String>read(record.value(), "$.aggregateId")).isEqualTo(record.key());
                assertThat(TopicReader.header(record, "schemaVersion")).isEqualTo("1");
            }
        }
        assertThat(jdbcTemplate.queryForList("""
                select attempt_count from outbox_event where aggregate_id = ? and published_at is not null""",
                Integer.class, Long.toString(orderId))).containsExactly(1, 1);
    }

    @Test
    void kafkaUnavailableLeavesRowsPendingAndALaterPollPublishesThem() throws Exception {
        long orderId = paidOrder();
        DefaultKafkaProducerFactory<String, String> unreachable = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000));
        try (TopicReader reader = reader()) {
            OutboxPublisher brokerDown = publisherWith(repository,
                    new KafkaEventSender(new KafkaTemplate<>(unreachable), properties));

            OutboxPublisher.BatchResult result = brokerDown.publishPendingBatch();

            assertThat(result.failed()).isEqualTo(1);
            Map<String, Object> created = row(orderId, 1);
            assertThat(created.get("published_at")).isNull();
            assertThat(created.get("attempt_count")).isEqualTo(1);
            assertThat((String) created.get("last_error")).contains("Kafka");
            assertThat(created.get("next_attempt_at")).isNotNull();
            // The business transaction is not affected at all.
            mockMvc.perform(getWithSession("/api/orders/{id}", orderId))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("PAID"));

            // Broker back (the real publisher): after the backoff both events go out, in order.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                publisher.publishAllPending(10);
                assertThat(row(orderId, 2).get("published_at")).isNotNull();
            });
            assertThat(row(orderId, 1).get("attempt_count")).isEqualTo(2);
            assertThat(reader.await(record -> record.key().equals(Long.toString(orderId)), 2, Duration.ofSeconds(10)))
                    .extracting(record -> TopicReader.header(record, "eventType")).containsExactly("OrderCreated", "OrderPaid");
        } finally {
            unreachable.destroy();
        }
    }

    @Test
    void crashAfterSendBeforeMarkPublishesTheSameEventTwice() throws Exception {
        long orderId = paidOrder();
        AtomicBoolean crashed = new AtomicBoolean();
        // Kafka accepted the record, then the process "dies" before published_at is committed.
        OutboxRepository crashingBeforeMark = new OutboxRepository(jdbcTemplate) {
            @Override
            public void markPublished(long id) {
                if (crashed.compareAndSet(false, true)) {
                    throw new IllegalStateException("simulated crash after Kafka send, before mark-published");
                }
                super.markPublished(id);
            }
        };
        try (TopicReader reader = reader()) {
            assertThatThrownBy(() -> publisherWith(crashingBeforeMark, kafkaSender).publishPendingBatch())
                    .hasMessageContaining("simulated crash");
            assertThat(row(orderId, 1).get("published_at")).isNull();   // the claim transaction rolled back

            publisher.publishAllPending(10);   // a later poll (or another instance) sends the row again

            List<ConsumerRecord<String, String>> created = reader.await(record -> record.key().equals(Long.toString(orderId))
                    && "OrderCreated".equals(TopicReader.header(record, "eventType")), 2, Duration.ofSeconds(10));
            // Two Kafka records, one logical event: same eventId. Only consumer-side dedup makes this harmless.
            assertThat(created).extracting(record -> TopicReader.header(record, "eventId"))
                    .containsOnly(row(orderId, 1).get("event_id").toString());
            assertThat(created.get(1).offset()).isGreaterThan(created.get(0).offset());
        }
        assertThat(row(orderId, 1).get("published_at")).isNotNull();
    }

    @Test
    void twoPublisherWorkersNeverClaimTheSameRow() throws Exception {
        List<String> aggregates = appendEventsForNewAggregates(10);
        Map<String, List<Long>> sentByWorker = new ConcurrentHashMap<>();
        CountDownLatch bothClaimed = new CountDownLatch(2);

        Future<OutboxPublisher.BatchResult> workerA = inBackground(() -> worker("A", 5, sentByWorker, bothClaimed).publishPendingBatch());
        Future<OutboxPublisher.BatchResult> workerB = inBackground(() -> worker("B", 5, sentByWorker, bothClaimed).publishPendingBatch());

        assertThat(workerA.get(20, TimeUnit.SECONDS).published()).isEqualTo(5);
        assertThat(workerB.get(20, TimeUnit.SECONDS).published()).isEqualTo(5);
        Set<Long> claimedByA = Set.copyOf(sentByWorker.get("A"));
        Set<Long> claimedByB = Set.copyOf(sentByWorker.get("B"));
        assertThat(claimedByA).hasSize(5).doesNotContainAnyElementsOf(claimedByB);
        assertThat(claimedByB).hasSize(5);
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbox_event where aggregate_id in ("
                + String.join(",", aggregates.stream().map(id -> "'" + id + "'").toList()) + ") and published_at is not null",
                Integer.class)).isEqualTo(10);
    }

    @Test
    void aSecondWorkerCannotOvertakeTheHeadEventOfAnAggregateHeldByTheFirst() throws Exception {
        String aggregate = "head-of-line-" + UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outboxWriter.append("Order", aggregate, "OrderCreated", 1, Map.of("n", 1));
            outboxWriter.append("Order", aggregate, "OrderPaid", 1, Map.of("n", 2));
        });
        CountDownLatch firstIsSending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OutboxPublisher holdingFirst = publisherWith(repository, record -> {
            firstIsSending.countDown();
            awaitLatch(release);
        });

        Future<OutboxPublisher.BatchResult> first = inBackground(holdingFirst::publishPendingBatch);
        assertThat(firstIsSending.await(10, TimeUnit.SECONDS)).isTrue();
        // Event 1 is claimed (locked) but not yet published; event 2 must not be claimable by anybody.
        List<String> claimedBySecond = new CopyOnWriteArrayList<>();
        OutboxPublisher.BatchResult second = publisherWith(repository, record -> claimedBySecond.add(record.eventType()))
                .publishPendingBatch();
        release.countDown();

        assertThat(second.claimed()).isZero();
        assertThat(claimedBySecond).isEmpty();
        assertThat(first.get(10, TimeUnit.SECONDS).published()).isEqualTo(1);
    }

    private OutboxPublisher worker(String name, int batchSize, Map<String, List<Long>> sentByWorker, CountDownLatch bothClaimed) {
        AtomicBoolean first = new AtomicBoolean(true);
        OutboxProperties.Publisher config = new OutboxProperties.Publisher(false, Duration.ofSeconds(1), batchSize,
                Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(1));
        return new OutboxPublisher(repository, record -> {
            sentByWorker.computeIfAbsent(name, ignored -> new CopyOnWriteArrayList<>()).add(record.id());
            if (first.getAndSet(false)) {
                // Keep this worker's claim (row locks) open until the other worker has claimed its batch too.
                bothClaimed.countDown();
                awaitLatch(bothClaimed);
            }
        }, new TransactionTemplate(transactionManager), config);
    }

    private List<String> appendEventsForNewAggregates(int count) {
        List<String> aggregates = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                String aggregate = "worker-test-" + UUID.randomUUID();
                outboxWriter.append("Order", aggregate, "OrderCreated", 1, Map.of("n", i));
                aggregates.add(aggregate);
            }
        });
        return aggregates;
    }

    private OutboxPublisher publisherWith(OutboxRepository outboxRepository, EventSender sender) {
        return new OutboxPublisher(outboxRepository, sender, new TransactionTemplate(transactionManager), properties.publisher());
    }

    private long paidOrder() throws Exception {
        Product lamp = createProduct("Published Lamp", "10.00", 10);
        addToCart(user, lamp.getId(), 1);
        String body = mockMvc.perform(checkout()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("PAID");
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private Map<String, Object> row(long orderId, int sequence) {
        return jdbcTemplate.queryForMap("""
                select event_id, published_at, attempt_count, last_error, next_attempt_at from outbox_event
                where aggregate_type = 'Order' and aggregate_id = ? and sequence = ?""", Long.toString(orderId), sequence);
    }

    private TopicReader reader() {
        return new TopicReader(String.join(",", kafka.getBootstrapServers()), TOPIC);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
