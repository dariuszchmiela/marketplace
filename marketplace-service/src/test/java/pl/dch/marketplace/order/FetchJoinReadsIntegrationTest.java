package pl.dch.marketplace.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.cart.Cart;
import pl.dch.marketplace.cart.CartItem;
import pl.dch.marketplace.cart.CartRepository;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hot read paths load their collections with an explicit JPQL fetch join: one SQL statement, collections initialized
 * before the repository call returns (open-session-in-view is off, so a lazy load in the web layer would fail), owner
 * filtering and ordering unchanged. They replaced {@code @EntityGraph(attributePaths = …)}, whose named-graph lookup
 * threw an exception on every call (see docs/production-diagnostics.md). The {@code …ForUpdate} lock queries
 * deliberately keep no fetch join.
 * <p>
 * Repository calls run outside any transaction here, exactly like the controllers' services see the returned entities.
 */
class FetchJoinReadsIntegrationTest extends IntegrationTestBase {

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Statistics statistics;
    private PersistenceUnitUtil persistence;

    @BeforeEach
    void enableStatementCounting() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        persistence = entityManagerFactory.getPersistenceUnitUtil();
    }

    @AfterEach
    void disableStatementCounting() {
        statistics.setStatisticsEnabled(false);
    }

    @Test
    void cartIsReadWithItsItemsInOneQueryInInsertionOrder() throws Exception {
        Product third = createProduct("Fetch Lamp C", "30.00", 10);
        Product first = createProduct("Fetch Lamp A", "10.00", 10);
        Product second = createProduct("Fetch Lamp B", "20.00", 10);
        addToCart(user, third.getId(), 1);   // item ids grow in insertion order, product ids do not
        addToCart(user, first.getId(), 2);
        addToCart(user, second.getId(), 3);

        Cart cart = oneQuery(() -> cartRepository.findBySessionId(uuid(session))).orElseThrow();

        assertThat(persistence.isLoaded(cart, "items")).isTrue();
        assertThat(cart.getItems()).extracting(CartItem::getProductId)
                .containsExactly(third.getId(), first.getId(), second.getId());
        assertThat(cart.getItems()).extracting(CartItem::getQuantity).containsExactly(1, 2, 3);
    }

    @Test
    void anEmptyCartIsStillFoundAndAnotherUsersCartIsNotReturned() throws Exception {
        Product lamp = createProduct("Fetch Lamp", "10.00", 10);
        addToCart(user, lamp.getId(), 1);
        mockMvc.perform(deleteWithSession("/api/cart/items/{productId}", lamp.getId()));   // cart row stays, no items
        TestUser other = signUp();

        Optional<Cart> empty = cartRepository.findBySessionId(uuid(session));

        assertThat(empty).hasValueSatisfying(cart -> assertThat(cart.getItems()).isEmpty());   // left join
        assertThat(cartRepository.findBySessionId(uuid(other.shoppingSessionId()))).isEmpty();   // never someone else's
    }

    @Test
    void orderListIsNewestFirstWithLinesInOneQueryAndNoDuplicateOrders() throws Exception {
        long older = placeOrder(3);
        long newer = placeOrder(2);
        TestUser other = signUp();
        placeOrderAs(other);

        List<Order> orders = oneQuery(() -> orderRepository.findAllBySessionIdOrderByCreatedAtDescIdDesc(uuid(session)));

        // distinct: 2 orders, not one row per line; only this user's orders; newest first
        assertThat(orders).extracting(Order::getId).containsExactly(newer, older);
        assertThat(orders).allSatisfy(order -> assertThat(persistence.isLoaded(order, "lines")).isTrue());
        assertThat(orders.get(0).getLines()).hasSize(2);
        assertThat(orders.get(1).getLines()).hasSize(3);
        assertThat(orders).allSatisfy(order -> assertThat(order.getLines()).extracting(OrderLine::getId).isSorted());
    }

    @Test
    void singleOrderIsReadWithItsLinesAndOnlyByItsOwner() throws Exception {
        long orderId = placeOrder(3);
        TestUser other = signUp();

        Order order = oneQuery(() -> orderRepository.findByIdAndSessionId(orderId, uuid(session))).orElseThrow();

        assertThat(persistence.isLoaded(order, "lines")).isTrue();
        assertThat(order.getLines()).hasSize(3).extracting(OrderLine::getId).isSorted();
        assertThat(orderRepository.findByIdAndSessionId(orderId, uuid(other.shoppingSessionId()))).isEmpty();
        assertThat(orderRepository.findByIdAndSessionId(Long.MAX_VALUE, uuid(session))).isEmpty();
    }

    @Test
    void checkoutKeyLookupReturnsTheOrderWithItsLinesAndIsScopedToTheOwner() throws Exception {
        UUID checkoutKey = UUID.randomUUID();
        Product lamp = createProduct("Fetch Key Lamp", "10.00", 10);
        Product desk = createProduct("Fetch Key Desk", "99.00", 10);
        addToCart(user, lamp.getId(), 1);
        addToCart(user, desk.getId(), 1);
        long orderId = orderId(mockMvc.perform(checkout(checkoutKey)).andReturn().getResponse().getContentAsString());
        TestUser other = signUp();

        Order order = oneQuery(() -> orderRepository.findBySessionIdAndCheckoutIdempotencyKey(uuid(session), checkoutKey))
                .orElseThrow();

        assertThat(order.getId()).isEqualTo(orderId);
        assertThat(persistence.isLoaded(order, "lines")).isTrue();
        assertThat(order.getLines()).extracting(OrderLine::getProductId).containsExactly(lamp.getId(), desk.getId());
        assertThat(orderRepository.findBySessionIdAndCheckoutIdempotencyKey(uuid(other.shoppingSessionId()), checkoutKey))
                .isEmpty();
    }

    @Test
    void lockQueriesStillLoadNoCollectionsInTheirSelectForUpdate() throws Exception {
        long orderId = placeOrder(1);
        addToCart(user, createProduct("Fetch Lock Lamp", "10.00", 10).getId(), 1);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Cart cart = oneQuery(() -> cartRepository.findBySessionIdForUpdate(uuid(session))).orElseThrow();
            Order order = oneQuery(() -> orderRepository.findByIdForUpdate(orderId)).orElseThrow();

            // No fetch join (PostgreSQL cannot lock the nullable side of an outer join): collections stay lazy and are
            // loaded after the row lock is held, inside the same transaction.
            assertThat(persistence.isLoaded(cart, "items")).isFalse();
            assertThat(persistence.isLoaded(order, "lines")).isFalse();
            assertThat(cart.getItems()).hasSize(1);
            assertThat(order.getLines()).hasSize(1);
        });
    }

    /** Runs the repository call and asserts it issued exactly one SQL statement. */
    private <T> T oneQuery(Supplier<T> repositoryCall) {
        statistics.clear();
        T result = repositoryCall.get();
        assertThat(statistics.getPrepareStatementCount()).as("SQL statements").isEqualTo(1);
        return result;
    }

    private long placeOrder(int lines) throws Exception {
        for (int i = 0; i < lines; i++) {
            addToCart(user, createProduct("Fetch Order Item " + i, "5.00", 10).getId(), 1);
        }
        return orderId(mockMvc.perform(checkout()).andReturn().getResponse().getContentAsString());
    }

    private void placeOrderAs(TestUser someone) throws Exception {
        addToCart(someone, createProduct("Fetch Other Item", "5.00", 10).getId(), 1);
        mockMvc.perform(checkoutAs(someone));
    }

    private static long orderId(String json) {
        return JsonPath.<Number>read(json, "$.id").longValue();
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
