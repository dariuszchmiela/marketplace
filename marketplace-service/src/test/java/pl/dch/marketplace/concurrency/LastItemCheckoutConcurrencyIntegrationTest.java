package pl.dch.marketplace.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Two (or more) shoppers buying the same last units at the same moment, over real HTTP (MockMvc),
 * real PostgreSQL and the real checkout code. Different sessions, so their carts are independent:
 * only {@code Product.@Version} stands between them.
 */
class LastItemCheckoutConcurrencyIntegrationTest extends IntegrationTestBase {

    @Test
    void twoShoppersBuyingTheLastUnitAtTheSameTimeExactlyOneWins() throws Exception {
        Product lamp = createProduct("Last Lamp", "10.00", 1);
        long versionBefore = versionOf(lamp);
        String shopperA = UUID.randomUUID().toString();
        String shopperB = UUID.randomUUID().toString();
        addToCart(shopperA, lamp.getId(), 1);
        addToCart(shopperB, lamp.getId(), 1);

        Future<MvcResult> checkoutA;
        Future<MvcResult> checkoutB;
        // Both checkout transactions read "stock = 1, version = v" and then queue on the UPDATE of the
        // product row held here. Only then is the lock released: a real race, not two sequential calls.
        try (RowLockHolder ignored = RowLockHolder.lock(dataSource,
                "select id from product where id = ? for update", lamp.getId())) {
            checkoutA = inBackground(() -> mockMvc.perform(checkoutAs(shopperA)).andReturn());
            checkoutB = inBackground(() -> mockMvc.perform(checkoutAs(shopperB)).andReturn());
            RowLockHolder.awaitLockWaiters(jdbcTemplate, 2);
        }

        List<MvcResult> results = List.of(checkoutA.get(20, TimeUnit.SECONDS), checkoutB.get(20, TimeUnit.SECONDS));
        MvcResult winner = results.stream().filter(result -> status(result) == 201).findFirst().orElseThrow();
        MvcResult loser = results.stream().filter(result -> result != winner).findFirst().orElseThrow();

        assertThat(JsonPath.<String>read(body(winner), "$.status")).isEqualTo("PAID");
        assertThat(status(loser)).isEqualTo(409);
        assertThat(JsonPath.<String>read(body(loser), "$.code")).isEqualTo("CONCURRENT_STOCK_CHANGE");
        assertThat(JsonPath.<String>read(body(loser), "$.message")).contains("try again").doesNotContain("Hibernate", "version");

        assertThat(stockOf(lamp)).isZero();
        assertThat(versionOf(lamp)).isEqualTo(versionBefore + 1);
        assertThat(PAYMENT_SERVICE.postRequests()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from order_line where product_id = ?", Integer.class, lamp.getId())).isEqualTo(1);
        // The loser's transaction was rolled back completely: no order, cart unchanged.
        String loserSession = loser == results.get(0) ? shopperA : shopperB;
        mockMvc.perform(get("/api/cart").header(SESSION_HEADER, loserSession))
                .andExpect(jsonPath("$.items", hasSize(1)));
        mockMvc.perform(get("/api/orders").header(SESSION_HEADER, loserSession))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void manyShoppersRacingForFewUnitsNeverOversellAndNeverFailWith500() throws Exception {
        int stock = 3;
        int shoppers = 10;
        Product lamp = createProduct("Contested Lamp", "10.00", stock);
        List<String> sessions = new ArrayList<>();
        for (int i = 0; i < shoppers; i++) {
            String shopper = UUID.randomUUID().toString();
            addToCart(shopper, lamp.getId(), 1);
            sessions.add(shopper);
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> checkouts = new ArrayList<>();
        for (String shopper : sessions) {
            checkouts.add(inBackground(() -> {
                start.await();
                return mockMvc.perform(checkoutAs(shopper)).andReturn();
            }));
        }
        start.countDown();

        int paid = 0;
        for (Future<MvcResult> checkout : checkouts) {
            MvcResult result = checkout.get(30, TimeUnit.SECONDS);
            if (status(result) == 201) {
                paid++;
            } else {
                // Lost the race on the version, or came after the stock was gone: both are clean 409s.
                assertThat(status(result)).isEqualTo(409);
                assertThat(JsonPath.<String>read(body(result), "$.code"))
                        .isIn("CONCURRENT_STOCK_CHANGE", "INSUFFICIENT_STOCK");
            }
        }

        System.out.printf("[RACE] %d shoppers, stock %d: %d paid, %d rejected with 409, stock left %d%n",
                shoppers, stock, paid, shoppers - paid, stockOf(lamp));
        // Optimistic locking may reject buyers although units are left (false conflicts), but it never oversells.
        assertThat(paid).isBetween(1, stock);
        assertThat(stockOf(lamp)).isEqualTo(stock - paid).isNotNegative();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from order_line where product_id = ?", Integer.class, lamp.getId())).isEqualTo(paid);
        assertThat(PAYMENT_SERVICE.postRequests()).hasSize(paid);
    }

    private long versionOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getVersion();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}
