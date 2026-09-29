package pl.dch.marketplace;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pl.dch.marketplace.payment.FakePaymentServer;
import pl.dch.marketplace.product.Product;
import pl.dch.marketplace.product.ProductRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application against a real PostgreSQL and Kafka (Testcontainers) with Flyway migrations applied, and a
 * {@link FakePaymentServer} on a real local port in place of payment-service.
 * <p>
 * Requests go through the real security chain: every test signs up its own user via the real endpoints
 * ({@code GET /api/auth/csrf} → {@code POST /api/auth/register} → fresh CSRF token) and sends the resulting
 * {@code MARKETPLACE_SESSION} cookie plus the {@code X-XSRF-TOKEN} header. Fresh users and own products isolate
 * the tests, so no database cleanup is needed.
 */
// No background outbox polling: outbox tests trigger publication explicitly and deterministically
// (a test class can switch it on with @TestPropertySource).
@SpringBootTest(properties = "outbox.publisher.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {

    protected static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    protected static final String TEST_PASSWORD = "correct horse battery staple";

    /** Shared by all integration tests (like the Spring context and the database container). */
    protected static final FakePaymentServer PAYMENT_SERVICE = FakePaymentServer.start();

    @DynamicPropertySource
    static void paymentServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("payment.client.base-url", PAYMENT_SERVICE::baseUrl);
        registry.add("payment.client.service-token", () -> FakePaymentServer.SERVICE_TOKEN);
        // A non-default value, so a test can prove the configured session timeout is really applied.
        registry.add("spring.session.timeout", () -> "20m");
        // Short values keep the timeout and retry tests fast.
        registry.add("payment.client.read-timeout", () -> "500ms");
        registry.add("payment.client.retry.initial-backoff", () -> "10ms");
        registry.add("outbox.publisher.initial-retry-backoff", () -> "50ms");
        registry.add("outbox.publisher.send-timeout", () -> "12s");
    }

    /**
     * A signed-up, logged-in user.
     *
     * @param shoppingSessionId the internal owner key of the user's cart and orders — only for SQL assertions;
     *                          the API never receives or returns it
     */
    protected record TestUser(long userId, String email, TestBrowser browser, String shoppingSessionId) {
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

    /** The test's default logged-in user. */
    protected TestUser user;

    /** {@code user}'s internal shopping session id, for SQL assertions on cart/orders ({@code session_id}). */
    protected String session;

    // For concurrent requests; MockMvc itself is thread safe.
    private final ExecutorService requestThreads = Executors.newCachedThreadPool();

    @BeforeEach
    void resetPaymentService() {
        PAYMENT_SERVICE.reset();
        // The circuit breaker is a singleton in the shared context; failures of one test must not leak.
        paymentCircuitBreaker.reset();
    }

    @BeforeEach
    void signInDefaultUser() throws Exception {
        user = signUp();
        session = user.shoppingSessionId();
    }

    @AfterEach
    void stopRequestThreads() {
        requestThreads.shutdownNow();
    }

    /** Registers a new user through the real API (CSRF → register → fresh CSRF), exactly like the frontend. */
    protected TestUser signUp() throws Exception {
        TestBrowser browser = new TestBrowser(mockMvc);
        String email = "user-" + UUID.randomUUID() + "@example.com";
        browser.refreshCsrfToken();
        MvcResult registered = browser.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(email, TEST_PASSWORD)));
        assertThat(registered.getResponse().getStatus()).as(registered.getResponse().getContentAsString()).isEqualTo(201);
        browser.refreshCsrfToken();   // the token is replaced on login/registration
        long userId = ((Number) JsonPath.read(registered.getResponse().getContentAsString(), "$.id")).longValue();
        String shoppingSessionId = jdbcTemplate.queryForObject(
                "select shopping_session_id::text from app_user where id = ?", String.class, userId);
        return new TestUser(userId, email, browser, shoppingSessionId);
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

    /** A request as the given user: session cookie, CSRF cookie and (for unsafe methods) the CSRF header. */
    protected MockHttpServletRequestBuilder as(TestUser someone, MockHttpServletRequestBuilder request) {
        return someone.browser().prepare(request);
    }

    protected MockHttpServletRequestBuilder getWithSession(String url, Object... vars) {
        return as(user, get(url, vars));
    }

    protected MockHttpServletRequestBuilder postWithSession(String url, String json) {
        return as(user, post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected MockHttpServletRequestBuilder putWithSession(String url, String json, Object... vars) {
        return as(user, put(url, vars).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected MockHttpServletRequestBuilder deleteWithSession(String url, Object... vars) {
        return as(user, delete(url, vars));
    }

    /** A new checkout attempt (fresh idempotency key). */
    protected MockHttpServletRequestBuilder checkout() {
        return checkout(UUID.randomUUID());
    }

    protected MockHttpServletRequestBuilder checkout(UUID idempotencyKey) {
        return as(user, post("/api/checkout").header(IDEMPOTENCY_KEY_HEADER, idempotencyKey));
    }

    /** Checkout of another user (fresh idempotency key). */
    protected MockHttpServletRequestBuilder checkoutAs(TestUser someone) {
        return as(someone, post("/api/checkout").header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID()));
    }

    protected void addToCart(TestUser someone, long productId, int quantity) throws Exception {
        mockMvc.perform(as(someone, post("/api/cart/items").contentType(MediaType.APPLICATION_JSON)
                        .content(addItemJson(productId, quantity))))
                .andExpect(status().isOk());
    }

    protected MockHttpServletRequestBuilder reconcilePayment(long orderId) {
        return as(user, post("/api/orders/{id}/reconcile-payment", orderId));
    }

    protected static String credentialsJson(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}""".formatted(email, password);
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
