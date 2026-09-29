package pl.dch.marketplace.observability;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.TestBrowser;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static pl.dch.marketplace.payment.FakePaymentServer.decline;
import static pl.dch.marketplace.payment.FakePaymentServer.succeed;
import static pl.dch.marketplace.payment.FakePaymentServer.succeedButRespondAfter;

/**
 * Custom metrics against the real application: every outcome lands in the right bounded series, and nothing that
 * identifies a user, order, payment, event or session ever becomes a metric label.
 * <p>
 * The registry is shared by all tests in the context, so assertions compare deltas.
 */
class BusinessMetricsIntegrationTest extends IntegrationTestBase {

    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern UUID_LIKE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void everyCheckoutOutcomeIsCountedAndTimed() throws Exception {
        double paid = checkouts("PAID");
        double failed = checkouts("PAYMENT_FAILED");
        double unknown = checkouts("PAYMENT_UNKNOWN");
        double replayed = checkouts("REPLAYED");
        double rejected = checkouts("REJECTED");
        long paidTimed = checkoutTimer("PAID").count();
        Product lamp = createProduct("Metrics Lamp", "10.00", 100);

        PAYMENT_SERVICE.respondWith(succeed());
        addToCart(user, lamp.getId(), 1);
        UUID key = UUID.randomUUID();
        mockMvc.perform(checkout(key));
        mockMvc.perform(checkout(key));                                   // same key: replay

        PAYMENT_SERVICE.respondWith(decline());
        addToCart(user, lamp.getId(), 1);
        mockMvc.perform(checkout());

        // The read timeout in tests is 500ms: the answer comes too late, the result is unknown.
        PAYMENT_SERVICE.respondWith(succeedButRespondAfter(Duration.ofMillis(1500)));
        addToCart(user, lamp.getId(), 1);   // (the declined order also put its item back into the cart)
        mockMvc.perform(checkout());

        TestUser emptyCart = signUp();
        mockMvc.perform(checkoutAs(emptyCart));                           // 422 CART_EMPTY: nothing ordered

        assertThat(checkouts("PAID") - paid).isEqualTo(1);
        assertThat(checkouts("REPLAYED") - replayed).isEqualTo(1);
        assertThat(checkouts("PAYMENT_FAILED") - failed).isEqualTo(1);
        assertThat(checkouts("PAYMENT_UNKNOWN") - unknown).isEqualTo(1);
        assertThat(checkouts("REJECTED") - rejected).isEqualTo(1);
        assertThat(checkoutTimer("PAID").count() - paidTimed).isEqualTo(1);
        // Where the time went: the two transactions and the remote call are timed separately.
        assertThat(meterRegistry.get("marketplace.checkout.step.duration").tag("step", "payment_call").timer().count())
                .isPositive();
        assertThat(meterRegistry.get("marketplace.checkout.step.duration").tag("step", "place_order").timer().count())
                .isPositive();
        // The timeout shows up in the payment client's own metrics, as a bounded result.
        assertThat(meterRegistry.get("payment.client.calls").tags("operation", "pay", "result", "timeout")
                .counter().count()).isPositive();
    }

    @Test
    void loginAndCsrfOutcomesAreCountedWithoutIdentifyingAnyone() throws Exception {
        double successes = counter("auth.login.success");
        double failures = counter("auth.login.failure");
        double csrfRejections = counter("security.csrf.rejected");
        double unauthenticated = counter("security.authentication.required");

        TestBrowser browser = new TestBrowser(mockMvc);
        browser.refreshCsrfToken();
        browser.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(user.email(), "wrong password!")));
        browser.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(user.email(), TEST_PASSWORD)));
        mockMvc.perform(post("/api/cart/items").cookie(new Cookie(TestBrowser.SESSION_COOKIE, user.browser().cookie(TestBrowser.SESSION_COOKIE)))
                .contentType(MediaType.APPLICATION_JSON).content(addItemJson(1, 1)));   // no CSRF header
        mockMvc.perform(get("/api/cart"));                                             // no session

        assertThat(counter("auth.login.failure") - failures).isEqualTo(1);
        assertThat(counter("auth.login.success") - successes).isEqualTo(1);
        assertThat(counter("security.csrf.rejected") - csrfRejections).isEqualTo(1);
        assertThat(counter("security.authentication.required") - unauthenticated).isEqualTo(1);
        for (String name : List.of("auth.login.success", "auth.login.failure", "security.csrf.rejected")) {
            assertThat(meterRegistry.get(name).counter().getId().getTags())
                    .extracting(tag -> tag.getKey())
                    .containsOnly("application");
        }
    }

    @Test
    void noIdentifierOrSecretEverBecomesAMetricLabel() throws Exception {
        // Traffic that touches every instrumented path: ids in URLs, checkout, payment, failed login, 404, CSRF.
        Product lamp = createProduct("Label Lamp", "10.00", 10);
        addToCart(user, lamp.getId(), 1);
        String order = mockMvc.perform(checkout()).andReturn().getResponse().getContentAsString();
        long orderId = com.jayway.jsonpath.JsonPath.<Number>read(order, "$.id").longValue();
        mockMvc.perform(getWithSession("/api/orders/{id}", orderId));
        mockMvc.perform(getWithSession("/api/orders/{id}", 987654321));
        mockMvc.perform(get("/api/products/{id}", lamp.getId()));
        mockMvc.perform(post("/api/orders/{id}/reconcile-payment", orderId));
        TestBrowser stranger = new TestBrowser(mockMvc);
        stranger.refreshCsrfToken();
        stranger.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(user.email(), "wrong password!")));

        String scrape = mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MANAGEMENT_TOKEN))
                .andReturn().getResponse().getContentAsString();

        Set<String> keys = new HashSet<>();
        Set<String> values = new HashSet<>();
        Matcher matcher = LABEL.matcher(scrape);
        while (matcher.find()) {
            keys.add(matcher.group(1));
            values.add(matcher.group(2));
        }
        assertThat(keys).doesNotContain("email", "user", "userId", "user_id", "sessionId", "session_id", "orderId",
                "order_id", "paymentId", "payment_id", "eventId", "event_id", "idempotencyKey", "traceId", "trace_id");
        assertThat(values).noneMatch(value -> UUID_LIKE.matcher(value).find());
        assertThat(values).noneMatch(value -> value.contains("@"));
        assertThat(values).noneMatch(value -> value.contains(Long.toString(orderId)) && value.startsWith("/"));
        assertThat(values).noneMatch(value -> value.contains("987654321"));
        assertThat(values).noneMatch(value -> value.contains("Bearer"));
        assertThat(scrape)
                .doesNotContain(user.email())
                .doesNotContain(TEST_PASSWORD)
                .doesNotContain(MANAGEMENT_TOKEN)
                .doesNotContain(pl.dch.marketplace.payment.FakePaymentServer.SERVICE_TOKEN)
                .doesNotContain(user.browser().cookie(TestBrowser.SESSION_COOKIE))
                .doesNotContain(session);
        // URI templates, not raw paths.
        assertThat(values).contains("/api/orders/{id}", "/api/products/{id}");
    }

    private double checkouts(String result) {
        Counter counter = meterRegistry.find("marketplace.checkout.total").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private Timer checkoutTimer(String result) {
        return meterRegistry.get("marketplace.checkout.duration").tag("result", result).timer();
    }

    private double counter(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }
}
