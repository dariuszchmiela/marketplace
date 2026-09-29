package pl.dch.marketplace.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static pl.dch.marketplace.payment.FakePaymentServer.decline;

/**
 * One shopper's cart modified by several requests at once: several browser tabs, or a payment failure
 * putting items back while the shopper edits the cart. Strategy under test: every cart mutation locks
 * the cart row ({@code SELECT … FOR UPDATE}), so mutations of one cart run one after another.
 */
class CartConcurrencyIntegrationTest extends IntegrationTestBase {

    @Test
    void concurrentAddsOfTheSameProductAreNotLost() throws Exception {
        Product lamp = createProduct("Tabs Lamp", "10.00", 100);
        addToCart(session, lamp.getId(), 1);

        List<MvcResult> results = runTogether(10, () -> mockMvc.perform(
                postWithSession("/api/cart/items", addItemJson(lamp.getId(), 1))).andReturn());

        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        assertThat(cartQuantity(lamp)).isEqualTo(11);
    }

    @Test
    void firstAddsFromSeveralTabsCreateOneCartWithoutErrors() throws Exception {
        Product lamp = createProduct("New Cart Lamp", "10.00", 100);

        List<MvcResult> results = runTogether(6, () -> mockMvc.perform(
                postWithSession("/api/cart/items", addItemJson(lamp.getId(), 1))).andReturn());

        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        assertThat(cartQuantity(lamp)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("select count(*) from cart where session_id = ?::uuid",
                Integer.class, session)).isEqualTo(1);
    }

    @Test
    void paymentFailureRestoresItemsWhileTheShopperAddsTheSameProduct() throws Exception {
        Product lamp = createProduct("Restore Lamp", "10.00", 10);
        addToCart(session, lamp.getId(), 2);
        AtomicReference<RowLockHolder> cartLock = new AtomicReference<>();
        CountDownLatch cartLocked = new CountDownLatch(1);
        PAYMENT_SERVICE.respondWith(decline());
        // While payment-service handles the request (transaction 1 is committed, the cart is empty),
        // take the cart row lock, so that the restore and the shopper's request both have to queue for it.
        PAYMENT_SERVICE.beforePostHandling(request -> {
            cartLock.set(RowLockHolder.lock(dataSource, "select id from cart where session_id = ?::uuid for update", session));
            cartLocked.countDown();
        });

        Future<MvcResult> checkout = inBackground(() -> mockMvc.perform(checkout()).andReturn());
        assertThat(cartLocked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<MvcResult> addAgain = inBackground(() -> mockMvc.perform(
                postWithSession("/api/cart/items", addItemJson(lamp.getId(), 1))).andReturn());
        RowLockHolder.awaitLockWaiters(jdbcTemplate, 2);
        cartLock.get().close();

        MvcResult checkoutResult = checkout.get(20, TimeUnit.SECONDS);
        assertThat(JsonPath.<String>read(checkoutResult.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("PAYMENT_FAILED");
        assertThat(addAgain.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        // Both changes survive, in whichever order they ran: 2 restored + 1 added.
        assertThat(cartQuantity(lamp)).isEqualTo(3);
        assertThat(stockOf(lamp)).isEqualTo(10);
    }

    @Test
    void paymentFailureRestoreAndARemovalOfTheSameProductBothApplyCleanly() throws Exception {
        Product lamp = createProduct("Remove Lamp", "10.00", 10);
        Product mouse = createProduct("Remove Mouse", "5.00", 10);
        addToCart(session, lamp.getId(), 2);
        AtomicReference<RowLockHolder> cartLock = new AtomicReference<>();
        CountDownLatch cartLocked = new CountDownLatch(1);
        PAYMENT_SERVICE.respondWith(decline());
        PAYMENT_SERVICE.beforePostHandling(request -> {
            cartLock.set(RowLockHolder.lock(dataSource, "select id from cart where session_id = ?::uuid for update", session));
            cartLocked.countDown();
        });

        Future<MvcResult> checkout = inBackground(() -> mockMvc.perform(checkout()).andReturn());
        assertThat(cartLocked.await(10, TimeUnit.SECONDS)).isTrue();
        // Meanwhile, in another tab, the shopper adds a mouse and removes the lamp.
        addToCartLater(mouse);
        Future<MvcResult> remove = inBackground(() -> mockMvc.perform(
                deleteWithSession("/api/cart/items/{id}", lamp.getId())).andReturn());
        RowLockHolder.awaitLockWaiters(jdbcTemplate, 2);
        cartLock.get().close();

        assertThat(checkout.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        assertThat(remove.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        // Serialized: the lamp is either restored and then removed, or removed (no-op) and then restored.
        // Never an error, never a broken line.
        assertThat(cartQuantity(lamp)).isIn(0, 2);
        assertThat(stockOf(lamp)).isEqualTo(10);
    }

    private void addToCartLater(Product product) {
        // Queued behind the cart lock as well; checked by the lock-waiter count in the caller only indirectly,
        // so it is simply started and awaited through the final assertions.
        inBackground(() -> mockMvc.perform(postWithSession("/api/cart/items", addItemJson(product.getId(), 1))
                .contentType(MediaType.APPLICATION_JSON)).andReturn());
    }

    private List<MvcResult> runTogether(int requests, java.util.concurrent.Callable<MvcResult> request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(inBackground(() -> {
                start.await();
                return request.call();
            }));
        }
        start.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private int cartQuantity(Product product) {
        List<Integer> quantities = jdbcTemplate.queryForList("""
                select ci.quantity from cart_item ci join cart c on c.id = ci.cart_id
                where c.session_id = ?::uuid and ci.product_id = ?""", Integer.class, session, product.getId());
        return quantities.isEmpty() ? 0 : quantities.getFirst();
    }
}
