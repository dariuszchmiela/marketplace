package pl.dch.marketplace.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.checkout.OrderPaymentUpdater;
import pl.dch.marketplace.checkout.OrderPlacementService;
import pl.dch.marketplace.payment.PaymentOutcome;
import pl.dch.marketplace.product.Product;
import pl.dch.marketplace.session.SessionId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.dch.marketplace.payment.FakePaymentServer.decline;
import static pl.dch.marketplace.payment.FakePaymentServer.fail;

/**
 * Which outbox rows the business transactions write. The publisher is off in these tests, so every row
 * written stays visible as "pending": nothing here touches Kafka.
 */
class OrderOutboxEventsIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OrderPlacementService orderPlacement;

    @Autowired
    private OrderPaymentUpdater orderPaymentUpdater;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void paidCheckoutWritesOrderCreatedThenOrderPaid() throws Exception {
        Product lamp = productInCart("Outbox Lamp", "79.90", 2);

        long orderId = orderId(mockMvc.perform(checkout()).andReturn());

        List<Map<String, Object>> events = outboxRows(orderId);
        assertThat(events).extracting(row -> row.get("event_type")).containsExactly("OrderCreated", "OrderPaid");
        assertThat(events).extracting(row -> row.get("sequence")).containsExactly(1, 2);
        assertThat(events).allSatisfy(row -> assertThat(row.get("published_at")).isNull());

        String created = (String) events.get(0).get("payload");
        assertThat(JsonPath.<String>read(created, "$.eventType")).isEqualTo("OrderCreated");
        assertThat(JsonPath.<Integer>read(created, "$.schemaVersion")).isEqualTo(1);
        assertThat(JsonPath.<String>read(created, "$.aggregateType")).isEqualTo("Order");
        assertThat(JsonPath.<String>read(created, "$.aggregateId")).isEqualTo(Long.toString(orderId));
        assertThat(JsonPath.<String>read(created, "$.eventId")).isEqualTo(events.get(0).get("event_id").toString());
        assertThat(JsonPath.<Number>read(created, "$.payload.orderId").longValue()).isEqualTo(orderId);
        assertThat(JsonPath.<String>read(created, "$.payload.status")).isEqualTo("PAYMENT_PENDING");
        assertThat(JsonPath.<Number>read(created, "$.payload.total").doubleValue()).isEqualTo(159.80);
        assertThat(JsonPath.<String>read(created, "$.payload.currency")).isEqualTo("PLN");
        assertThat(JsonPath.<Number>read(created, "$.payload.lines[0].productId").longValue()).isEqualTo(lamp.getId());
        // The anonymous session id is a bearer identifier: it must never leave the service in an event.
        assertThat(created).doesNotContain(session);

        String paid = (String) events.get(1).get("payload");
        assertThat(JsonPath.<String>read(paid, "$.payload.status")).isEqualTo("PAID");
        assertThat(JsonPath.<String>read(paid, "$.payload.paymentId")).isNotBlank();
    }

    @Test
    void orderCreatedIsCommittedWithTheOrderBeforePaymentAndWithoutKafka() throws Exception {
        productInCart("Commit Lamp", "10.00", 1);
        List<String> seenDuringPayment = new CopyOnWriteArrayList<>();
        // Runs while the marketplace waits for payment-service, i.e. after transaction 1 committed.
        PAYMENT_SERVICE.beforePostHandling(request -> seenDuringPayment.addAll(jdbcTemplate.queryForList("""
                select event_type || ':' || coalesce(published_at::text, 'pending') from outbox_event
                where aggregate_type = 'Order' and aggregate_id = ?""", String.class,
                Long.toString(request.json().get("orderId").asLong()))));

        mockMvc.perform(checkout()).andReturn();

        assertThat(seenDuringPayment).containsExactly("OrderCreated:pending");
    }

    @Test
    void declinedAndNotProcessedPaymentsWriteOneOrderPaymentFailed() throws Exception {
        productInCart("Declined Outbox Lamp", "10.00", 1);
        PAYMENT_SERVICE.respondWith(decline());
        long declined = orderId(mockMvc.perform(checkout()).andReturn());

        productInCart("Unavailable Outbox Lamp", "10.00", 1);
        PAYMENT_SERVICE.respondWith(fail(503));
        long notProcessed = orderId(mockMvc.perform(checkout()).andReturn());

        assertThat(eventTypes(declined)).containsExactly("OrderCreated", "OrderPaymentFailed");
        assertThat(JsonPath.<String>read(payload(declined, "OrderPaymentFailed"), "$.payload.reason")).isEqualTo("DECLINED");
        assertThat(eventTypes(notProcessed)).containsExactly("OrderCreated", "OrderPaymentFailed");
        assertThat(JsonPath.<String>read(payload(notProcessed, "OrderPaymentFailed"), "$.payload.reason")).isEqualTo("NOT_PROCESSED");
    }

    @Test
    void unknownThenReconciledWritesUnknownThenPaidAndNothingForFinalReplays() throws Exception {
        productInCart("Unknown Outbox Lamp", "10.00", 1);
        UUID key = UUID.randomUUID();
        PAYMENT_SERVICE.respondWith(fail(500));
        long orderId = orderId(mockMvc.perform(checkout(key)).andReturn());
        assertThat(eventTypes(orderId)).containsExactly("OrderCreated", "OrderPaymentUnknown");

        // Reconciliation without a payment: still unknown, nothing new is known -> no event.
        mockMvc.perform(reconcilePayment(orderId)).andReturn();
        assertThat(eventTypes(orderId)).containsExactly("OrderCreated", "OrderPaymentUnknown");

        String paymentKey = PAYMENT_SERVICE.postRequests().getFirst().idempotencyKey();
        PAYMENT_SERVICE.storePayment(paymentKey, orderId, new java.math.BigDecimal("10.00"), "SUCCEEDED");
        mockMvc.perform(reconcilePayment(orderId)).andReturn();
        assertThat(eventTypes(orderId)).containsExactly("OrderCreated", "OrderPaymentUnknown", "OrderPaid");
        assertThat(outboxRows(orderId)).extracting(row -> row.get("sequence")).containsExactly(1, 2, 3);

        // Final order: a second reconciliation and a replayed checkout write nothing.
        mockMvc.perform(reconcilePayment(orderId)).andReturn();
        mockMvc.perform(checkout(key)).andReturn();
        assertThat(eventTypes(orderId)).hasSize(3);
    }

    @Test
    void replayedCheckoutKeyWritesNoNewEvents() throws Exception {
        productInCart("Replay Outbox Lamp", "10.00", 1);
        UUID key = UUID.randomUUID();
        long orderId = orderId(mockMvc.perform(checkout(key)).andReturn());

        mockMvc.perform(checkout(key)).andReturn();
        mockMvc.perform(checkout(key)).andReturn();

        assertThat(eventTypes(orderId)).containsExactly("OrderCreated", "OrderPaid");
    }

    @Test
    void concurrentOutcomesForOneOrderWriteExactlyOneEvent() throws Exception {
        productInCart("Concurrent Outbox Lamp", "10.00", 1);
        PAYMENT_SERVICE.respondWith(fail(500));
        long orderId = orderId(mockMvc.perform(checkout()).andReturn());
        int threads = 6;
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(inBackground(() -> {
                start.await(10, TimeUnit.SECONDS);
                return orderPaymentUpdater.applyOutcome(orderId, new PaymentOutcome.Declined("pay-x"));
            }));
        }
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }

        assertThat(eventTypes(orderId)).containsExactly("OrderCreated", "OrderPaymentUnknown", "OrderPaymentFailed");
    }

    @Test
    void rolledBackTransactionLeavesNeitherOrderNorOutboxEvent() throws Exception {
        productInCart("Rollback Outbox Lamp", "10.00", 1);
        int outboxBefore = count("select count(*) from outbox_event");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderPlacement.placeOrder(SessionId.of(session), UUID.randomUUID());   // order + OrderCreated written...
            throw new IllegalStateException("simulated failure before commit");   // ...and rolled back together
        })).hasMessageContaining("simulated failure");

        assertThat(count("select count(*) from outbox_event")).isEqualTo(outboxBefore);
        assertThat(count("select count(*) from orders where session_id = '" + session + "'::uuid")).isZero();
    }

    @Test
    void eventsCanOnlyBeWrittenInsideATransaction() {
        assertThatThrownBy(() -> outboxWriter.append("Order", "1", "OrderCreated", 1, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Product productInCart(String name, String price, int quantity) throws Exception {
        Product product = createProduct(name, price, 10);
        addToCart(user, product.getId(), quantity);
        return product;
    }

    private List<Map<String, Object>> outboxRows(long orderId) {
        return jdbcTemplate.queryForList("""
                select event_id, event_type, sequence, payload::text as payload, published_at from outbox_event
                where aggregate_type = 'Order' and aggregate_id = ? order by sequence""", Long.toString(orderId));
    }

    private List<Object> eventTypes(long orderId) {
        return outboxRows(orderId).stream().map(row -> row.get("event_type")).toList();
    }

    private String payload(long orderId, String eventType) {
        return outboxRows(orderId).stream().filter(row -> eventType.equals(row.get("event_type")))
                .map(row -> (String) row.get("payload")).findFirst().orElseThrow();
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private static long orderId(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
