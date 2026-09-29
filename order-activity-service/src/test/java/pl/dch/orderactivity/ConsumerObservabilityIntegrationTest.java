package pl.dch.orderactivity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import pl.dch.orderactivity.activity.OrderActivityRepository;
import pl.dch.orderactivity.simulation.FailureSimulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static pl.dch.orderactivity.TestEvents.orderCreated;
import static pl.dch.orderactivity.TestEvents.orderPaid;

/**
 * Consumer metrics (processed / duplicate / stale / retry / dead-lettered, bounded tags), Kafka client lag metrics,
 * trace continuation from the producer's traceparent header, and the management endpoint boundary.
 */
// Same context configuration as OrderActivityConsumerIntegrationTest: one cached context, one set of containers.
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ConsumerObservabilityIntegrationTest {

    private static final String TOPIC = "marketplace.order-events";
    private static final String MANAGEMENT = "Bearer dev-only-management-token";   // the local default
    private static final Duration CATCH_UP = Duration.ofSeconds(20);
    private static final AtomicLong ORDER_IDS = new AtomicLong(System.nanoTime() % 1_000_000 * 1000 + 500_000_000L);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private OrderActivityRepository activities;

    @Autowired
    private FailureSimulator failureSimulator;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Tracer tracer;

    @Autowired
    private Propagator propagator;

    @AfterEach
    void cleanUp() {
        failureSimulator.reset();
    }

    @Test
    void everyOutcomeIsCountedPerEventType() {
        double processed = counter("order.events.processed", "OrderCreated");
        double duplicates = counter("order.events.duplicate", "OrderCreated");
        double stale = counter("order.events.stale", "OrderCreated");
        long orderId = ORDER_IDS.incrementAndGet();
        String created = orderCreated(UUID.randomUUID(), orderId, 1);

        send(orderId, created, "OrderCreated", null);
        send(orderId, created, "OrderCreated", null);                                  // redelivered copy
        send(orderId, orderPaid(UUID.randomUUID(), orderId, 2), "OrderPaid", null);
        send(orderId, orderCreated(UUID.randomUUID(), orderId, 1), "OrderCreated", null); // replayed, older

        await().atMost(CATCH_UP).untilAsserted(() -> {
            assertThat(counter("order.events.processed", "OrderCreated") - processed).isEqualTo(1);
            assertThat(counter("order.events.duplicate", "OrderCreated") - duplicates).isEqualTo(1);
            assertThat(counter("order.events.stale", "OrderCreated") - stale).isEqualTo(1);
        });
        assertThat(meterRegistry.get("order.events.processing").tags("eventType", "OrderPaid", "result", "applied")
                .timer().count()).isPositive();
    }

    @Test
    void transientFailuresAreCountedAsRetriesAndExhaustedOnesAsDeadLettered() {
        double retries = counter("order.events.retry", "OrderCreated");
        double deadLettered = meterRegistry.find("order.events.dead_lettered")
                .tags("eventType", "OrderCreated", "reason", "retries_exhausted").counter() == null ? 0
                : meterRegistry.get("order.events.dead_lettered").tags("eventType", "OrderCreated", "reason", "retries_exhausted")
                        .counter().count();
        long recovering = ORDER_IDS.incrementAndGet();
        long failing = ORDER_IDS.incrementAndGet();
        failureSimulator.failTransiently(Long.toString(recovering), 1);
        failureSimulator.failTransiently(Long.toString(failing), 100);

        send(recovering, orderCreated(UUID.randomUUID(), recovering, 1), "OrderCreated", null);
        send(failing, orderCreated(UUID.randomUUID(), failing, 1), "OrderCreated", null);

        await().atMost(CATCH_UP).untilAsserted(() -> {
            assertThat(activities.find(recovering)).isPresent();
            // 1 failed attempt for the recovering event + 4 failed attempts (1 delivery + 3 retries) for the failing one
            assertThat(counter("order.events.retry", "OrderCreated") - retries).isEqualTo(5);
            assertThat(meterRegistry.get("order.events.dead_lettered")
                    .tags("eventType", "OrderCreated", "reason", "retries_exhausted").counter().count() - deadLettered)
                    .isEqualTo(1);
        });
    }

    @Test
    void anInvalidEventIsDeadLetteredAsPermanentAndForeignEventTypesAreNotLabels() {
        long orderId = ORDER_IDS.incrementAndGet();
        String hostileType = "Evil-" + UUID.randomUUID();

        send(orderId, "{ this is not json", hostileType, null);

        await().atMost(CATCH_UP).untilAsserted(() -> assertThat(meterRegistry.find("order.events.dead_lettered")
                .tags("eventType", "other", "reason", "permanent").counter()).isNotNull());
        assertThat(meterRegistry.getMeters()).noneMatch(meter -> meter.getId().getTags().stream()
                .anyMatch(tag -> tag.getValue().contains(hostileType)));
    }

    @Test
    void theListenerContinuesTheProducersTrace(CapturedOutput output) {
        long orderId = ORDER_IDS.incrementAndGet();
        String traceId = "5bf92f3577b34da6a3ce929d0e0e4737";

        send(orderId, orderCreated(UUID.randomUUID(), orderId, 1), "OrderCreated", "00-" + traceId + "-00f067aa0ba902b7-01");

        await().atMost(CATCH_UP).untilAsserted(() -> assertThat(output.getOut().lines()
                .filter(line -> line.contains("event.processed") && line.contains("orderId=" + orderId)))
                .singleElement().asString().contains("traceId=" + traceId));
    }

    @Test
    void metricsNeedTheManagementTokenAndShowConsumerLag() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer test-activity-token-or-anything"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env").header("Authorization", MANAGEMENT)).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/heapdump").header("Authorization", MANAGEMENT)).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/health/dependencies").header("Authorization", MANAGEMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.kafkaConsumer.details.running").isNumber())
                .andExpect(jsonPath("$.components.db.status").value("UP"));

        long orderId = ORDER_IDS.incrementAndGet();
        send(orderId, orderCreated(UUID.randomUUID(), orderId, 1), "OrderCreated", null);
        await().atMost(CATCH_UP).untilAsserted(() -> assertThat(activities.find(orderId)).isPresent());

        await().atMost(CATCH_UP).untilAsserted(() -> {
            String scrape = mockMvc.perform(get("/actuator/prometheus").header("Authorization", MANAGEMENT))
                    .andReturn().getResponse().getContentAsString();
            assertThat(scrape)
                    .contains("order_events_processed_total")
                    .contains("kafka_consumer_fetch_manager_records_lag_max")
                    .contains("spring_kafka_listener_seconds")
                    .contains("hikaricp_connections_active")
                    .doesNotContain("orderId=\"")
                    .doesNotContain("eventId=\"");
        });
    }

    private void send(long orderId, String event, String eventType, String traceparent) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, Long.toString(orderId), event);
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        if (traceparent == null) {
            kafkaTemplate.send(record).join();
            return;
        }
        // Like the marketplace publisher: the send happens inside a span restored from a stored traceparent, and the
        // instrumented KafkaTemplate writes the W3C header of its producer span (same trace) into the record.
        Span restored = propagator.extract(Map.of("traceparent", traceparent), Map::get).name("test publish").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(restored)) {
            kafkaTemplate.send(record).join();
        } finally {
            restored.end();
        }
    }

    private double counter(String name, String eventType) {
        Counter counter = meterRegistry.find(name).tag("eventType", eventType).counter();
        return counter == null ? 0 : counter.count();
    }
}
