package pl.dch.marketplace.productionlab;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import pl.dch.marketplace.payment.FakePaymentServer;
import pl.dch.marketplace.payment.PaymentClient;
import pl.dch.marketplace.payment.PaymentClientMetrics;
import pl.dch.marketplace.payment.PaymentOutcome;
import pl.dch.marketplace.payment.RemotePayment;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.dch.marketplace.payment.FakePaymentServer.succeed;
import static pl.dch.marketplace.payment.FakePaymentServer.succeedButRespondAfter;

/**
 * Lab: a slow payment downstream, seen through the application's own {@code payment.client.duration{result}} timer —
 * the real {@link PaymentClient} (timeouts, circuit breaker, metrics) against a local fake payment-service.
 * <ol>
 *   <li><b>normal</b>: every payment answers immediately;</li>
 *   <li><b>5% slow</b>: 1 in 20 answers after 1 s; the read timeout (300 ms here, 2 s in the application) turns them
 *       into {@code result=timeout} / PAYMENT_UNKNOWN. The <em>mean</em> barely moves (+ 5% × 300 ms ≈ +15 ms) while
 *       <em>p99</em> jumps to the timeout: averages hide exactly the requests users complain about;</li>
 *   <li><b>payment-service slow</b>: every call times out; after the minimum number of calls the circuit opens and the
 *       remaining calls fail fast ({@code result=circuit_open}) — the latency drops, the outcome is "not processed".
 *       The circuit breaker protects the caller's threads, not the payment.</li>
 * </ol>
 * The same experiment against the running stack uses payment-service's SLOW scenario and the load generator
 * (docs/production-diagnostics.md).
 */
@Tag(ProductionLab.TAG)
class SlowPaymentDownstreamLabTest {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final int CALLS = 200;
    private static final int CALLERS = 8;

    private static FakePaymentServer server;

    @BeforeAll
    static void start() {
        server = FakePaymentServer.start();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void tailLatencyGrowsLongBeforeTheAverageLooksBad() throws Exception {
        Phase normal = run("normal", everyNth(CALLS, 0), 1000);
        Phase fivePercentSlow = run("5% slow (1 s > 300 ms timeout)", everyNth(CALLS, 20), 1000);

        assertThat(normal.results).containsOnlyKeys("success");
        assertThat(fivePercentSlow.results.get("timeout")).isEqualTo(CALLS / 20);
        // The mean grows by roughly 5% of the timeout, the p99 by the whole timeout.
        assertThat(fivePercentSlow.latencies.meanMs() - normal.latencies.meanMs()).isLessThan(READ_TIMEOUT.toMillis() / 5.0);
        assertThat(fivePercentSlow.latencies.p99Ms()).isGreaterThanOrEqualTo(READ_TIMEOUT.toMillis() * 0.9);
        assertThat(normal.latencies.p99Ms()).isLessThan(READ_TIMEOUT.toMillis() / 2.0);
    }

    @Test
    void whenEverythingIsSlowTheCircuitOpensAndCallsFailFast() throws Exception {
        Phase slow = run("payment-service SLOW (all 1 s)", everyNth(CALLS, 1), 10);

        // Until the circuit opened: the 10 recorded failures plus calls that were already permitted (in flight) by then.
        assertThat(slow.results.get("timeout")).isBetween(10, 10 + 2 * CALLERS);
        assertThat(slow.results.get("circuit_open")).isGreaterThan(CALLS / 2);
        assertThat(slow.circuitState).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(slow.latencies.p50Ms()).isLessThan(READ_TIMEOUT.toMillis() / 2.0);   // most calls: instant refusal
    }

    /** Behaviour list: every n-th POST answers after 1 s (n = 0: none, n = 1: all). */
    private static List<FakePaymentServer.Behaviour> everyNth(int calls, int n) {
        List<FakePaymentServer.Behaviour> behaviours = new ArrayList<>();
        for (int i = 1; i <= calls; i++) {
            behaviours.add(n > 0 && i % n == 0 ? succeedButRespondAfter(Duration.ofSeconds(1)) : succeed());
        }
        behaviours.add(succeed());
        return behaviours;
    }

    private record Phase(ProductionLab.Latencies latencies, Map<String, Integer> results, CircuitBreaker.State circuitState) {
    }

    private Phase run(String name, List<FakePaymentServer.Behaviour> behaviours, int circuitMinimumCalls) throws Exception {
        server.reset();
        server.respondWith(behaviours.toArray(FakePaymentServer.Behaviour[]::new));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // What management.metrics.distribution.percentiles does in the application: client-side p50/p95/p99.
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                return DistributionStatisticConfig.builder().percentiles(0.5, 0.95, 0.99).build().merge(config);
            }
        });
        CircuitBreaker circuitBreaker = CircuitBreaker.of("lab-payment", CircuitBreakerConfig.custom()
                .slidingWindowSize(circuitMinimumCalls).minimumNumberOfCalls(circuitMinimumCalls).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1)).build());
        PaymentClient client = new PaymentClient(restClient(), circuitBreaker,
                Retry.of("lab-payment", RetryConfig.custom().maxAttempts(1).build()), false, new PaymentClientMetrics(registry));

        ConcurrentLinkedQueue<Long> nanos = new ConcurrentLinkedQueue<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(CALLERS)) {   // like CALLERS busy request threads
            List<Future<PaymentOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < CALLS; i++) {
                futures.add(executor.submit(() -> {
                    long start = System.nanoTime();
                    PaymentOutcome outcome = client.pay(new RemotePayment.Request(1L, new BigDecimal("10.00"), "PLN",
                            UUID.randomUUID()), null);
                    nanos.add(System.nanoTime() - start);
                    return outcome;
                }));
            }
            for (Future<PaymentOutcome> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        Map<String, Integer> results = new TreeMap<>();
        for (Counter counter : registry.find("payment.client.calls").tag("operation", "pay").counters()) {
            if (counter.count() > 0) {   // all results are pre-registered with 0
                results.put(counter.getId().getTag("result"), (int) counter.count());
            }
        }
        ProductionLab.Latencies latencies = ProductionLab.Latencies.of(nanos);
        ProductionLab.print(name, "results " + results + ", circuit " + circuitBreaker.getState());
        ProductionLab.print("  latency (exact)", latencies.toString());
        for (Timer timer : registry.find("payment.client.duration").timers()) {
            StringBuilder line = new StringBuilder();
            for (ValueAtPercentile value : timer.takeSnapshot().percentileValues()) {
                line.append(String.format(Locale.ROOT, " p%.0f=%.0f", value.percentile() * 100, value.value(TimeUnit.MILLISECONDS)));
            }
            ProductionLab.print("  payment.client.duration{" + timer.getId().getTag("result") + "}",
                    "count " + timer.count() + line + String.format(Locale.ROOT, " mean=%.1f ms", timer.mean(TimeUnit.MILLISECONDS)));
        }
        return new Phase(latencies, results, circuitBreaker.getState());
    }

    private static RestClient restClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)).version(HttpClient.Version.HTTP_1_1).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(server.baseUrl())
                .requestFactory(factory)
                .observationRegistry(ObservationRegistry.NOOP)
                .defaultHeader("Authorization", "Bearer " + FakePaymentServer.SERVICE_TOKEN)
                .build();
    }
}
