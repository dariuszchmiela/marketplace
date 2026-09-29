package pl.dch.marketplace.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.checkout.OrderPaymentUpdater;
import pl.dch.marketplace.order.OrderResponse;
import pl.dch.marketplace.order.OrderStatus;
import pl.dch.marketplace.payment.PaymentOutcome;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.dch.marketplace.payment.FakePaymentServer.decline;
import static pl.dch.marketplace.payment.FakePaymentServer.fail;

/**
 * The payment result of one order can arrive twice at the same time: from the checkout request itself
 * and from a reconciliation. The order row lock ({@code SELECT … FOR UPDATE}) plus the final-state check
 * must make exactly one of them win and run the compensation (stock + cart) at most once.
 */
class OrderPaymentRaceIntegrationTest extends IntegrationTestBase {

    private static final int INITIAL_STOCK = 10;
    private static final int ORDERED = 2;

    @Autowired
    private OrderPaymentUpdater orderPaymentUpdater;

    @Test
    void manyConcurrentDeclinedOutcomesCompensateExactlyOnce() throws Exception {
        Product lamp = createProduct("Twice Declined Lamp", "10.00", INITIAL_STOCK);
        long orderId = unknownOrder(lamp);

        List<OrderResponse> results = applyTogether(orderId,
                List.of(declined(), declined(), declined(), declined(), declined(), declined()));

        assertThat(results).allSatisfy(order -> assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_FAILED));
        assertThat(stockOf(lamp)).isEqualTo(INITIAL_STOCK);   // returned once, not six times
        assertThat(cartQuantity(lamp)).isEqualTo(ORDERED);     // restored once
    }

    @Test
    void conflictingConcurrentOutcomesLeaveOneConsistentFinalState() throws Exception {
        Product lamp = createProduct("Contested Order Lamp", "10.00", INITIAL_STOCK);
        long orderId = unknownOrder(lamp);

        List<OrderResponse> results = applyTogether(orderId,
                List.of(new PaymentOutcome.Succeeded("pay-ok"), declined()));

        // Whichever came first is final; the second one sees a final order and changes nothing.
        OrderStatus finalStatus = results.getFirst().status();
        assertThat(results).allSatisfy(order -> assertThat(order.status()).isEqualTo(finalStatus));
        if (finalStatus == OrderStatus.PAID) {
            assertThat(stockOf(lamp)).isEqualTo(INITIAL_STOCK - ORDERED);
            assertThat(cartQuantity(lamp)).isZero();
        } else {
            assertThat(finalStatus).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(stockOf(lamp)).isEqualTo(INITIAL_STOCK);
            assertThat(cartQuantity(lamp)).isEqualTo(ORDERED);
        }
    }

    @Test
    void checkoutResultAndReconciliationArrivingTogetherOverHttpCompensateOnce() throws Exception {
        Product lamp = createProduct("Double Result Lamp", "10.00", INITIAL_STOCK);
        addToCart(user, lamp.getId(), ORDERED);
        PAYMENT_SERVICE.respondWith(decline());
        AtomicReference<RowLockHolder> orderLock = new AtomicReference<>();
        AtomicReference<Long> orderId = new AtomicReference<>();
        CountDownLatch orderLocked = new CountDownLatch(1);
        // While payment-service handles the POST, lock the order row: the checkout's transaction 2 will
        // queue for it, and so will the reconciliation started below.
        PAYMENT_SERVICE.beforePostHandling(request -> {
            orderId.set(request.json().get("orderId").asLong());
            orderLock.set(RowLockHolder.lock(dataSource, "select id from orders where id = ? for update", orderId.get()));
            orderLocked.countDown();
        });

        Future<MvcResult> checkout = inBackground(() -> mockMvc.perform(checkout()).andReturn());
        assertThat(orderLocked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<MvcResult> reconciliation = inBackground(() -> mockMvc.perform(reconcilePayment(orderId.get())).andReturn());
        RowLockHolder.awaitLockWaiters(jdbcTemplate, 2);
        orderLock.get().close();

        assertThat(status(checkout.get(20, TimeUnit.SECONDS))).isEqualTo("PAYMENT_FAILED");
        assertThat(status(reconciliation.get(20, TimeUnit.SECONDS))).isEqualTo("PAYMENT_FAILED");
        assertThat(stockOf(lamp)).isEqualTo(INITIAL_STOCK);
        assertThat(cartQuantity(lamp)).isEqualTo(ORDERED);
    }

    @Test
    void noRowLockOrTransactionIsHeldWhilePaymentServiceIsCalled() throws Exception {
        Product lamp = createProduct("Lock Free Lamp", "10.00", INITIAL_STOCK);
        addToCart(user, lamp.getId(), 1);
        PAYMENT_SERVICE.respondWith(fail(500));   // -> PAYMENT_UNKNOWN, then reconcile
        List<String> lockedDuringRemoteCall = new CopyOnWriteArrayList<>();
        PAYMENT_SERVICE.beforePostHandling(request -> {
            long id = request.json().get("orderId").asLong();
            checkNotLocked("order during payment", "select id from orders where id = ? for update nowait", id, lockedDuringRemoteCall);
            checkNotLocked("cart during payment", "select id from cart where session_id = ?::uuid for update nowait", session, lockedDuringRemoteCall);
            checkNotLocked("product during payment", "select id from product where id = ? for update nowait", lamp.getId(), lockedDuringRemoteCall);
        });
        long orderId = orderId(mockMvc.perform(checkout()).andReturn());
        PAYMENT_SERVICE.beforeLookupHandling(request -> checkNotLocked("order during reconciliation",
                "select id from orders where id = ? for update nowait", orderId, lockedDuringRemoteCall));

        mockMvc.perform(reconcilePayment(orderId)).andReturn();

        assertThat(PAYMENT_SERVICE.postRequests()).hasSize(1);
        assertThat(PAYMENT_SERVICE.lookupRequests()).hasSize(1);
        assertThat(lockedDuringRemoteCall).isEmpty();
    }

    @Test
    void theNowaitProbeDetectsAHeldLock() {
        Product lamp = createProduct("Probe Lamp", "10.00", 1);
        List<String> locked = new CopyOnWriteArrayList<>();

        try (RowLockHolder ignored = RowLockHolder.lock(dataSource, "select id from product where id = ? for update", lamp.getId())) {
            checkNotLocked("product", "select id from product where id = ? for update nowait", lamp.getId(), locked);
        }

        assertThat(locked).containsExactly("product");
    }

    private void checkNotLocked(String what, String sql, Object argument, List<String> lockedDuringRemoteCall) {
        try (RowLockHolder ignored = RowLockHolder.lock(dataSource, sql, argument)) {
            // NOWAIT succeeded: nobody holds the row lock while we are inside the remote call.
        } catch (IllegalStateException ex) {
            lockedDuringRemoteCall.add(what);
        }
    }

    /** An order waiting for its result: stock taken, cart emptied. */
    private long unknownOrder(Product product) throws Exception {
        addToCart(user, product.getId(), ORDERED);
        PAYMENT_SERVICE.respondWith(fail(500));
        MvcResult result = mockMvc.perform(checkout()).andReturn();
        assertThat(status(result)).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(stockOf(product)).isEqualTo(INITIAL_STOCK - ORDERED);
        return orderId(result);
    }

    private List<OrderResponse> applyTogether(long orderId, List<PaymentOutcome> outcomes) throws Exception {
        CyclicBarrier start = new CyclicBarrier(outcomes.size());
        List<Future<OrderResponse>> futures = new ArrayList<>();
        for (PaymentOutcome outcome : outcomes) {
            futures.add(inBackground(() -> {
                start.await(10, TimeUnit.SECONDS);
                return orderPaymentUpdater.applyOutcome(orderId, outcome);
            }));
        }
        List<OrderResponse> results = new ArrayList<>();
        for (Future<OrderResponse> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }
        return results;
    }

    private static PaymentOutcome declined() {
        return new PaymentOutcome.Declined("pay-declined");
    }

    private int cartQuantity(Product product) {
        List<Integer> quantities = jdbcTemplate.queryForList("""
                select ci.quantity from cart_item ci join cart c on c.id = ci.cart_id
                where c.session_id = ?::uuid and ci.product_id = ?""", Integer.class, session, product.getId());
        return quantities.isEmpty() ? 0 : quantities.getFirst();
    }

    private static String status(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.status");
    }

    private static long orderId(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
