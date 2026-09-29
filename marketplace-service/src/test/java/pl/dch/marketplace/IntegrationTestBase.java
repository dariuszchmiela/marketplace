package pl.dch.marketplace;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pl.dch.marketplace.payment.FakePaymentServer;
import pl.dch.marketplace.product.Product;
import pl.dch.marketplace.product.ProductRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application against a real PostgreSQL (Testcontainers) with Flyway migrations applied, and a
 * {@link FakePaymentServer} on a real local port in place of payment-service.
 * Tests isolate themselves by using a fresh session id and their own products,
 * so no database cleanup between tests is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {

    protected static final String SESSION_HEADER = "X-Session-Id";
    protected static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Shared by all integration tests (like the Spring context and the database container). */
    protected static final FakePaymentServer PAYMENT_SERVICE = FakePaymentServer.start();

    @DynamicPropertySource
    static void paymentServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("payment.client.base-url", PAYMENT_SERVICE::baseUrl);
        // Short values keep the timeout and retry tests fast.
        registry.add("payment.client.read-timeout", () -> "500ms");
        registry.add("payment.client.retry.initial-backoff", () -> "10ms");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected CircuitBreaker paymentCircuitBreaker;

    @Autowired
    protected DataSource dataSource;

    protected final String session = UUID.randomUUID().toString();

    // For concurrent requests; MockMvc itself is thread safe.
    private final ExecutorService requestThreads = Executors.newCachedThreadPool();

    @BeforeEach
    void resetPaymentService() {
        PAYMENT_SERVICE.reset();
        // The circuit breaker is a singleton in the shared context; failures of one test must not leak.
        paymentCircuitBreaker.reset();
    }

    @AfterEach
    void stopRequestThreads() {
        requestThreads.shutdownNow();
    }

    protected <T> Future<T> inBackground(Callable<T> task) {
        return requestThreads.submit(task);
    }

    protected Product createProduct(String name, String price, int availableQuantity) {
        return productRepository.save(new Product(name, name + " description", new BigDecimal(price), availableQuantity));
    }

    protected int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getAvailableQuantity();
    }

    protected MockHttpServletRequestBuilder getWithSession(String url, Object... vars) {
        return get(url, vars).header(SESSION_HEADER, session);
    }

    protected MockHttpServletRequestBuilder postWithSession(String url, String json) {
        return post(url).header(SESSION_HEADER, session).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected MockHttpServletRequestBuilder putWithSession(String url, String json, Object... vars) {
        return put(url, vars).header(SESSION_HEADER, session).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected MockHttpServletRequestBuilder deleteWithSession(String url, Object... vars) {
        return delete(url, vars).header(SESSION_HEADER, session);
    }

    /** A new checkout attempt (fresh idempotency key). */
    protected MockHttpServletRequestBuilder checkout() {
        return checkout(UUID.randomUUID());
    }

    protected MockHttpServletRequestBuilder checkout(UUID idempotencyKey) {
        return post("/api/checkout").header(SESSION_HEADER, session).header(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
    }

    /** Checkout of another session (fresh idempotency key). */
    protected MockHttpServletRequestBuilder checkoutAs(String otherSession) {
        return post("/api/checkout").header(SESSION_HEADER, otherSession).header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID());
    }

    protected void addToCart(String anySession, long productId, int quantity) throws Exception {
        mockMvc.perform(post("/api/cart/items").header(SESSION_HEADER, anySession)
                        .contentType(MediaType.APPLICATION_JSON).content(addItemJson(productId, quantity)))
                .andExpect(status().isOk());
    }

    protected MockHttpServletRequestBuilder reconcilePayment(long orderId) {
        return post("/api/orders/{id}/reconcile-payment", orderId).header(SESSION_HEADER, session);
    }

    protected static String addItemJson(long productId, int quantity) {
        return """
                {"productId": %d, "quantity": %d}""".formatted(productId, quantity);
    }

    protected static String quantityJson(int quantity) {
        return """
                {"quantity": %d}""".formatted(quantity);
    }
}
