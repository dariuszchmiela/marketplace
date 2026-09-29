package pl.dch.marketplace.payment;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.dch.marketplace.payment.FakePaymentServer.decline;
import static pl.dch.marketplace.payment.FakePaymentServer.fail;
import static pl.dch.marketplace.payment.FakePaymentServer.succeed;
import static pl.dch.marketplace.payment.FakePaymentServer.succeedButRespondAfter;

/**
 * {@link PaymentClient} against a real local HTTP server, without Spring: timeouts, retry,
 * circuit breaker and the resulting {@link PaymentOutcome}.
 */
class PaymentClientTest {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    private static FakePaymentServer server;

    private final RemotePayment.Request request =
            new RemotePayment.Request(42L, new BigDecimal("129.50"), "PLN", UUID.randomUUID());

    private CircuitBreaker circuitBreaker;
    private SimpleMeterRegistry meterRegistry;
    private PaymentClient client;

    @BeforeAll
    static void startServer() {
        server = FakePaymentServer.start();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void setUp() {
        server.reset();
        // A circuit breaker that does not interfere with the non-circuit-breaker tests.
        client = client(server.baseUrl(), new PaymentClientProperties.CircuitBreaker(50, 100, 100, Duration.ofSeconds(10), 1), false);
    }

    @Test
    void successfulPayment() {
        PaymentOutcome outcome = client.pay(request, null);

        assertThat(outcome).isInstanceOf(PaymentOutcome.Succeeded.class);
        assertThat(server.postRequests()).singleElement().satisfies(sent -> {
            assertThat(sent.json().get("orderId").asLong()).isEqualTo(42L);
            assertThat(sent.json().get("amount").decimalValue()).isEqualByComparingTo("129.50");
            assertThat(sent.json().get("currency").asString()).isEqualTo("PLN");
            assertThat(sent.idempotencyKey()).isEqualTo(request.idempotencyKey().toString());
        });
    }

    @Test
    void everyPaymentAndReconciliationCallCarriesTheServiceToken() {
        server.respondWith(fail(503), succeed());

        client.pay(request, null);
        client.findByIdempotencyKey(request.idempotencyKey());

        // Both attempts of the retried POST and the reconciliation GET are authenticated.
        assertThat(server.postRequests()).hasSize(2);
        assertThat(server.postRequests()).extracting(sent -> sent.header("Authorization"))
                .containsOnly("Bearer " + FakePaymentServer.SERVICE_TOKEN);
        assertThat(server.lookupRequests()).singleElement()
                .satisfies(sent -> assertThat(sent.header("Authorization")).isEqualTo("Bearer " + FakePaymentServer.SERVICE_TOKEN));
    }

    @Test
    void rejectedServiceTokenIsNotRetriedAndNothingWasProcessed() {
        PaymentClient wrongToken = client(server.baseUrl(),
                new PaymentClientProperties.CircuitBreaker(50, 100, 100, Duration.ofSeconds(10), 1), false, "wrong-token");

        // 401 is a 4xx: payment-service refused the request, so it provably did not process it; retrying cannot help.
        assertThat(wrongToken.pay(request, null)).isInstanceOf(PaymentOutcome.NotProcessed.class);
        assertThat(server.postRequests()).hasSize(1);
        assertThat(server.paymentCount()).isZero();
    }

    @Test
    void declinedPaymentIsABusinessResultAndIsNotRetried() {
        server.respondWith(decline());

        assertThat(client.pay(request, null)).isInstanceOf(PaymentOutcome.Declined.class);
        assertThat(server.postRequests()).hasSize(1);
    }

    @Test
    void transient503IsRetriedWithTheSameIdempotencyKey() {
        server.respondWith(fail(503), fail(503), succeed());

        PaymentOutcome outcome = client.pay(request, null);

        assertThat(outcome).isInstanceOf(PaymentOutcome.Succeeded.class);
        assertThat(server.postRequests()).hasSize(3)
                .extracting(FakePaymentServer.RecordedRequest::idempotencyKey)
                .containsOnly(request.idempotencyKey().toString());
        assertThat(server.paymentCount()).isEqualTo(1);
    }

    @Test
    void persistent503IsRetriedUpToMaxAttemptsAndProvesNothingWasProcessed() {
        server.respondWith(fail(503));

        PaymentOutcome outcome = client.pay(request, null);

        assertThat(outcome).isInstanceOf(PaymentOutcome.NotProcessed.class);
        assertThat(server.postRequests()).hasSize(3);
    }

    @Test
    void gatewayErrorIsRetriedButLeavesTheResultUnknown() {
        // 502 may come from a proxy after payment-service processed the request.
        server.respondWith(fail(502), fail(503), fail(503));

        assertThat(client.pay(request, null)).isInstanceOf(PaymentOutcome.Unknown.class);
        assertThat(server.postRequests()).hasSize(3);
    }

    @Test
    void internalServerErrorIsNotRetriedAndResultIsUnknown() {
        server.respondWith(fail(500));

        assertThat(client.pay(request, null)).isInstanceOf(PaymentOutcome.Unknown.class);
        assertThat(server.postRequests()).hasSize(1);
    }

    @Test
    void validationErrorIsNotRetried() {
        server.respondWith(fail(400));

        assertThat(client.pay(request, null)).isInstanceOf(PaymentOutcome.NotProcessed.class);
        assertThat(server.postRequests()).hasSize(1);
    }

    @Test
    void idempotencyConflictIsNotRetriedAndResultIsUnknown() {
        server.respondWith(fail(409));

        assertThat(client.pay(request, null)).isInstanceOf(PaymentOutcome.Unknown.class);
        assertThat(server.postRequests()).hasSize(1);
    }

    @Test
    void responseTimeoutLeavesTheResultUnknownAndIsNotRetried() {
        server.respondWith(succeedButRespondAfter(READ_TIMEOUT.multipliedBy(4)));
        long start = System.nanoTime();

        PaymentOutcome outcome = client.pay(request, null);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(outcome).isInstanceOfSatisfying(PaymentOutcome.Unknown.class,
                unknown -> assertThat(unknown.reason()).isEqualTo("response timeout"));
        assertThat(elapsed).isLessThan(READ_TIMEOUT.multipliedBy(3));
        assertThat(server.postRequests()).hasSize(1);
        // The payment exists although the client never saw it: this is what reconciliation is for.
        assertThat(server.paymentCount()).isEqualTo(1);
    }

    @Test
    void connectionRefusedIsRetriedAndProvesNothingWasProcessed() throws IOException {
        PaymentClient unreachable = client("http://localhost:" + freePort(),
                new PaymentClientProperties.CircuitBreaker(50, 100, 100, Duration.ofSeconds(10), 1), false);

        PaymentOutcome outcome = unreachable.pay(request, null);

        assertThat(outcome).isInstanceOfSatisfying(PaymentOutcome.NotProcessed.class,
                notProcessed -> assertThat(notProcessed.reason()).isEqualTo("connection failed"));
    }

    @Test
    void scenarioHeaderIsForwardedOnlyWhenEnabled() {
        client.pay(request, "DECLINED");
        client(server.baseUrl(), new PaymentClientProperties.CircuitBreaker(50, 100, 100, Duration.ofSeconds(10), 1), true)
                .pay(request, "DECLINED");

        assertThat(server.postRequests())
                .extracting(sent -> sent.header(PaymentClient.SCENARIO_HEADER))
                .containsExactly(null, "DECLINED");
    }

    @Test
    void circuitOpensAfterFailuresFailsFastAndClosesAfterSuccessfulHalfOpenCall() throws InterruptedException {
        Duration openDuration = Duration.ofMillis(200);
        PaymentClient guarded = client(server.baseUrl(),
                new PaymentClientProperties.CircuitBreaker(50, 4, 4, openDuration, 1), false);
        server.respondWith(fail(500));

        for (int i = 0; i < 4; i++) {
            guarded.pay(request, null);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Open: fails fast without calling payment-service, and nothing can have been charged.
        PaymentOutcome whileOpen = guarded.pay(request, null);
        assertThat(whileOpen).isInstanceOfSatisfying(PaymentOutcome.NotProcessed.class,
                notProcessed -> assertThat(notProcessed.reason()).isEqualTo("circuit breaker open"));
        assertThat(server.postRequests()).hasSize(4);

        // After the open duration one trial call is let through (half-open); it succeeds, so the circuit closes.
        Thread.sleep(openDuration.plusMillis(100));
        server.respondWith(succeed());
        assertThat(guarded.pay(request, null)).isInstanceOf(PaymentOutcome.Succeeded.class);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failedHalfOpenCallOpensTheCircuitAgain() throws InterruptedException {
        Duration openDuration = Duration.ofMillis(200);
        PaymentClient guarded = client(server.baseUrl(),
                new PaymentClientProperties.CircuitBreaker(50, 2, 2, openDuration, 1), false);
        server.respondWith(fail(500));
        guarded.pay(request, null);
        guarded.pay(request, null);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(openDuration.plusMillis(100));
        guarded.pay(request, null);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(server.postRequests()).hasSize(3);
    }

    @Test
    void clientErrorsDoNotOpenTheCircuit() {
        PaymentClient guarded = client(server.baseUrl(),
                new PaymentClientProperties.CircuitBreaker(50, 4, 4, Duration.ofSeconds(10), 1), false);
        server.respondWith(fail(400));

        for (int i = 0; i < 6; i++) {
            guarded.pay(request, null);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void lookupReturnsStoredPaymentOrEmpty() {
        client.pay(request, null);

        assertThat(client.findByIdempotencyKey(request.idempotencyKey()))
                .hasValueSatisfying(found -> assertThat(found.status()).isEqualTo(RemotePayment.Status.SUCCEEDED));
        assertThat(client.findByIdempotencyKey(UUID.randomUUID())).isEmpty();
    }

    @Test
    void lookupFailureIsReportedAsServiceUnavailable() {
        server.failLookupsWith(503);

        assertThatThrownBy(() -> client.findByIdempotencyKey(request.idempotencyKey()))
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(ErrorCode.PAYMENT_SERVICE_UNAVAILABLE);
    }

    @Test
    void everyLogicalCallIsMeasuredWithABoundedResultTag() {
        Duration openDuration = Duration.ofSeconds(10);
        PaymentClient guarded = client(server.baseUrl(), new PaymentClientProperties.CircuitBreaker(50, 5, 5, openDuration, 1), false);

        server.respondWith(succeed());
        guarded.pay(newRequest(), null);
        server.respondWith(decline());
        guarded.pay(newRequest(), null);
        server.respondWith(fail(503), fail(503), fail(503));   // one logical call, three attempts
        guarded.pay(newRequest(), null);
        guarded.pay(newRequest(), null);                           // 3 of 5 recorded calls failed: circuit open, fails fast
        assertThatThrownBy(() -> guarded.findByIdempotencyKey(UUID.randomUUID()))   // lookup fails fast too
                .isInstanceOf(MarketplaceException.class);

        assertThat(callCount("pay", "success")).isEqualTo(1);
        assertThat(callCount("pay", "declined")).isEqualTo(1);
        assertThat(callCount("pay", "server_error")).isEqualTo(1);
        assertThat(callCount("pay", "circuit_open")).isEqualTo(1);
        assertThat(callCount("lookup", "circuit_open")).isEqualTo(1);
        assertThat(meterRegistry.get(PaymentClientMetrics.DURATION).tags("operation", "pay", "result", "server_error")
                .timer().count()).isEqualTo(1);
        // Only bounded tags: operation and result. Never an order id, payment id or idempotency key.
        assertThat(meterRegistry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey())
                        .containsOnly("operation", "result"));
    }

    @Test
    void responseTimeoutIsMeasuredAsTimeout() {
        server.respondWith(succeedButRespondAfter(READ_TIMEOUT.multipliedBy(4)));

        client.pay(request, null);

        assertThat(callCount("pay", "timeout")).isEqualTo(1);
        assertThat(meterRegistry.get(PaymentClientMetrics.DURATION).tags("result", "timeout").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(READ_TIMEOUT.toMillis());
    }

    private static RemotePayment.Request newRequest() {
        return new RemotePayment.Request(42L, new BigDecimal("129.50"), "PLN", UUID.randomUUID());
    }

    private double callCount(String operation, String result) {
        var counter = meterRegistry.find(PaymentClientMetrics.CALLS).tags("operation", operation, "result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void refusesToCallPaymentServiceInsideADatabaseTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> client.pay(request, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("inside a database transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(server.postRequests()).isEmpty();
    }

    private PaymentClient client(String baseUrl, PaymentClientProperties.CircuitBreaker circuitBreakerProperties,
                                 boolean forwardScenarioHeader) {
        return client(baseUrl, circuitBreakerProperties, forwardScenarioHeader, FakePaymentServer.SERVICE_TOKEN);
    }

    private PaymentClient client(String baseUrl, PaymentClientProperties.CircuitBreaker circuitBreakerProperties,
                                 boolean forwardScenarioHeader, String serviceToken) {
        PaymentClientProperties properties = new PaymentClientProperties(
                URI.create(baseUrl),
                Duration.ofMillis(300),
                READ_TIMEOUT,
                forwardScenarioHeader,
                new PaymentClientProperties.Retry(3, Duration.ofMillis(10), 2.0, 0.5),
                circuitBreakerProperties,
                serviceToken);
        circuitBreaker = PaymentClientConfiguration.circuitBreaker(properties.circuitBreaker());
        meterRegistry = new SimpleMeterRegistry();
        return new PaymentClient(PaymentClientConfiguration.restClient(properties, ObservationRegistry.NOOP), circuitBreaker,
                PaymentClientConfiguration.retry(properties.retry()), properties.forwardScenarioHeader(),
                new PaymentClientMetrics(meterRegistry));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
