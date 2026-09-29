package pl.dch.marketplace.checkout;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.payment.FakePaymentServer;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static pl.dch.marketplace.payment.FakePaymentServer.decline;
import static pl.dch.marketplace.payment.FakePaymentServer.fail;
import static pl.dch.marketplace.payment.FakePaymentServer.succeed;
import static pl.dch.marketplace.payment.FakePaymentServer.succeedButRespondAfter;

/**
 * Checkout with payment against real PostgreSQL and a real HTTP payment-service stand-in.
 * The read timeout is 500 ms in these tests (see {@link IntegrationTestBase}).
 */
class PaymentCheckoutIntegrationTest extends IntegrationTestBase {

    private static final Duration LONGER_THAN_READ_TIMEOUT = Duration.ofMillis(1500);

    @Test
    void successfulPaymentMarksOrderPaid() throws Exception {
        Product lamp = productInCart("Paid Lamp", "79.90", 10, 2);

        long orderId = orderId(mockMvc.perform(checkout())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentId").isNotEmpty())
                .andExpect(jsonPath("$.paymentFailureReason").value(nullValue()))
                .andReturn());

        assertThat(PAYMENT_SERVICE.postRequests()).singleElement().satisfies(request -> {
            assertThat(request.json().get("orderId").asLong()).isEqualTo(orderId);
            assertThat(request.json().get("amount").decimalValue()).isEqualByComparingTo("159.80");
            assertThat(request.json().get("currency").asString()).isEqualTo("PLN");
            assertThat(UUID.fromString(request.idempotencyKey())).isNotNull();
        });
        assertThat(stockOf(lamp)).isEqualTo(8);
        assertCartItems(0);
    }

    @Test
    void declinedPaymentFailsTheOrderReturnsStockAndRestoresTheCart() throws Exception {
        Product lamp = productInCart("Declined Lamp", "10.00", 5, 2);
        PAYMENT_SERVICE.respondWith(decline());

        mockMvc.perform(checkout())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.paymentFailureReason").value("DECLINED"))
                .andExpect(jsonPath("$.paymentId").isNotEmpty());

        assertThat(PAYMENT_SERVICE.postRequests()).hasSize(1);
        assertThat(stockOf(lamp)).isEqualTo(5);
        mockMvc.perform(getWithSession("/api/cart"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].productId").value(lamp.getId()))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void transientServerErrorIsRetriedWithTheSamePaymentKey() throws Exception {
        productInCart("Retry Lamp", "10.00", 5, 1);
        PAYMENT_SERVICE.respondWith(fail(503), succeed());

        mockMvc.perform(checkout())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(PAYMENT_SERVICE.postRequests()).hasSize(2)
                .extracting(FakePaymentServer.RecordedRequest::idempotencyKey)
                .containsOnly(PAYMENT_SERVICE.postRequests().getFirst().idempotencyKey());
    }

    @Test
    void paymentServiceUnavailableAfterAllRetriesFailsTheOrderWithoutLosingAnything() throws Exception {
        Product lamp = productInCart("Unavailable Lamp", "10.00", 5, 3);
        PAYMENT_SERVICE.respondWith(fail(503));

        mockMvc.perform(checkout())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.paymentFailureReason").value("NOT_PROCESSED"));

        assertThat(PAYMENT_SERVICE.postRequests()).hasSize(3)
                .extracting(FakePaymentServer.RecordedRequest::idempotencyKey)
                .containsOnly(PAYMENT_SERVICE.postRequests().getFirst().idempotencyKey());
        assertThat(stockOf(lamp)).isEqualTo(5);
        assertCartItems(1);
    }

    @Test
    void timeoutAfterRecordedPaymentLeavesOrderUnknownUntilReconciliationMarksItPaid() throws Exception {
        Product lamp = productInCart("Slow Lamp", "10.00", 5, 2);
        PAYMENT_SERVICE.respondWith(succeedButRespondAfter(LONGER_THAN_READ_TIMEOUT));

        long orderId = orderId(mockMvc.perform(checkout())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_UNKNOWN"))
                .andExpect(jsonPath("$.paymentId").value(nullValue()))
                .andReturn());

        // A timeout is not retried, and the marketplace must not assume failure:
        // stock stays taken and the cart is not restored.
        assertThat(PAYMENT_SERVICE.postRequests()).hasSize(1);
        assertThat(stockOf(lamp)).isEqualTo(3);
        assertCartItems(0);

        mockMvc.perform(reconcilePayment(orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentId").isNotEmpty());
        assertThat(PAYMENT_SERVICE.lookupRequests()).singleElement()
                .satisfies(lookup -> assertThat(lookup.path())
                        .endsWith(PAYMENT_SERVICE.postRequests().getFirst().idempotencyKey()));

        // Reconciling a final order is a no-op and does not call payment-service again.
        mockMvc.perform(reconcilePayment(orderId))
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(PAYMENT_SERVICE.lookupRequests()).hasSize(1);
        mockMvc.perform(getWithSession("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(stockOf(lamp)).isEqualTo(3);
    }

    @Test
    void reconciliationOfADeclinedPaymentReturnsStockAndCart() throws Exception {
        Product lamp = productInCart("Late Decline Lamp", "10.00", 5, 1);
        PAYMENT_SERVICE.respondWith(fail(500));
        long orderId = orderId(mockMvc.perform(checkout())
                .andExpect(jsonPath("$.status").value("PAYMENT_UNKNOWN"))
                .andReturn());
        String paymentKey = PAYMENT_SERVICE.postRequests().getFirst().idempotencyKey();
        PAYMENT_SERVICE.storePayment(paymentKey, orderId, new BigDecimal("10.00"), "DECLINED");

        mockMvc.perform(reconcilePayment(orderId))
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.paymentFailureReason").value("DECLINED"));

        assertThat(stockOf(lamp)).isEqualTo(5);
        assertCartItems(1);
    }

    @Test
    void reconciliationWithoutAPaymentKeepsTheOrderUnknown() throws Exception {
        Product lamp = productInCart("Ambiguous Lamp", "10.00", 5, 1);
        PAYMENT_SERVICE.respondWith(fail(500));
        long orderId = orderId(mockMvc.perform(checkout())
                .andExpect(jsonPath("$.status").value("PAYMENT_UNKNOWN"))
                .andReturn());

        mockMvc.perform(reconcilePayment(orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_UNKNOWN"));

        assertThat(PAYMENT_SERVICE.lookupRequests()).hasSize(1);
        assertThat(stockOf(lamp)).isEqualTo(4);
    }

    @Test
    void reconciliationRefusesAPaymentThatDoesNotMatchTheOrder() throws Exception {
        productInCart("Mismatch Lamp", "10.00", 5, 1);
        PAYMENT_SERVICE.respondWith(fail(500));
        long orderId = orderId(mockMvc.perform(checkout()).andReturn());
        String paymentKey = PAYMENT_SERVICE.postRequests().getFirst().idempotencyKey();
        PAYMENT_SERVICE.storePayment(paymentKey, orderId, new BigDecimal("1.00"), "SUCCEEDED");

        mockMvc.perform(reconcilePayment(orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_RECONCILIATION_CONFLICT"));
        mockMvc.perform(getWithSession("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PAYMENT_UNKNOWN"));
    }

    @Test
    void reconciliationWhilePaymentServiceIsDownReturns503AndChangesNothing() throws Exception {
        productInCart("Down Lamp", "10.00", 5, 1);
        PAYMENT_SERVICE.respondWith(fail(500));
        long orderId = orderId(mockMvc.perform(checkout()).andReturn());
        PAYMENT_SERVICE.failLookupsWith(503);

        mockMvc.perform(reconcilePayment(orderId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_SERVICE_UNAVAILABLE"));
        mockMvc.perform(getWithSession("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PAYMENT_UNKNOWN"));
    }

    @Test
    void sameCheckoutKeyReturnsTheSameOrderWithoutASecondOrderOrPayment() throws Exception {
        Product lamp = productInCart("Idempotent Lamp", "10.00", 5, 1);
        UUID key = UUID.randomUUID();

        long orderId = orderId(mockMvc.perform(checkout(key))
                .andExpect(status().isCreated())
                .andReturn());
        // The shopper fills the cart again; replaying the old attempt must not check out the new cart.
        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(lamp.getId(), 2)));

        mockMvc.perform(checkout(key))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/api/orders/" + orderId))
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(PAYMENT_SERVICE.postRequests()).hasSize(1);
        mockMvc.perform(getWithSession("/api/orders")).andExpect(jsonPath("$", hasSize(1)));
        assertThat(stockOf(lamp)).isEqualTo(4);
        assertCartItems(1);
    }

    // Repeated: which of the race paths is taken (unique constraint, empty cart, ...) depends on timing.
    @RepeatedTest(5)
    void concurrentDuplicateCheckoutsCreateOneOrderAndOnePayment() throws Exception {
        Product lamp = productInCart("Concurrent Lamp", "10.00", 10, 2);
        UUID key = UUID.randomUUID();
        int requests = 6;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new CopyOnWriteArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(checkout(key)).andReturn();
                }));
            }
            start.countDown();

            List<Long> orderIds = new CopyOnWriteArrayList<>();
            int created = 0;
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(20, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isIn(200, 201);
                created += result.getResponse().getStatus() == 201 ? 1 : 0;
                orderIds.add(orderId(result));
            }

            assertThat(orderIds).containsOnly(orderIds.getFirst());
            assertThat(created).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(PAYMENT_SERVICE.postRequests()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from orders where session_id = ?::uuid",
                Integer.class, session)).isEqualTo(1);
        assertThat(stockOf(lamp)).isEqualTo(8);
    }

    @Test
    void orderIsCommittedBeforePaymentServiceIsCalled() throws Exception {
        productInCart("Boundary Lamp", "10.00", 5, 1);
        List<String> statusSeenByPaymentService = new CopyOnWriteArrayList<>();
        // Runs on the fake server's thread while the checkout request waits for the payment response.
        // It uses its own connection: under READ COMMITTED it only sees the order if transaction 1
        // was already committed, i.e. no transaction is open around the HTTP call.
        PAYMENT_SERVICE.beforePostHandling(request -> statusSeenByPaymentService.add(jdbcTemplate.queryForObject(
                "select status from orders where id = ?", String.class, request.json().get("orderId").asLong())));

        mockMvc.perform(checkout())
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(statusSeenByPaymentService).containsExactly("PAYMENT_PENDING");
    }

    @Test
    void openCircuitFailsFastWithoutCallingPaymentService() throws Exception {
        Product lamp = productInCart("Circuit Lamp", "10.00", 5, 1);
        paymentCircuitBreaker.transitionToOpenState();

        mockMvc.perform(checkout())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.paymentFailureReason").value("NOT_PROCESSED"));

        assertThat(PAYMENT_SERVICE.postRequests()).isEmpty();
        assertThat(stockOf(lamp)).isEqualTo(5);
        assertCartItems(1);
        assertThat(paymentCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void checkoutRequiresAValidIdempotencyKey() throws Exception {
        productInCart("Keyless Lamp", "10.00", 5, 1);

        mockMvc.perform(postWithSession("/api/checkout", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
        mockMvc.perform(postWithSession("/api/checkout", "").header(IDEMPOTENCY_KEY_HEADER, "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        assertThat(PAYMENT_SERVICE.postRequests()).isEmpty();
        assertCartItems(1);
    }

    private Product productInCart(String name, String price, int stock, int quantity) throws Exception {
        Product product = createProduct(name, price, stock);
        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(product.getId(), quantity)))
                .andExpect(status().isOk());
        return product;
    }

    private void assertCartItems(int count) throws Exception {
        mockMvc.perform(getWithSession("/api/cart")).andExpect(jsonPath("$.items", hasSize(count)));
    }

    private static long orderId(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
