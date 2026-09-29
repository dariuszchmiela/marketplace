package pl.dch.marketplace.productionlab;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import pl.dch.marketplace.lab.FakeDownstream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: a downstream that can process only 4 requests at a time (100 ms each) — like a payment provider with a fixed
 * worker pool or a rate limit. The callers are virtual threads: cheap and unlimited. Adding callers does not add
 * downstream capacity; it adds <em>queueing</em>, and queueing is what p95/p99 measure.
 * <pre>
 *   4 callers  → nobody waits          → p99 ≈ service time
 *   40 callers → 36 wait in line        → p50 grows, p99 ≈ (40 / 4) × 100 ms
 * </pre>
 * Throughput stays at capacity / service time (≈ 40 req/s) whatever the number of callers. Virtual threads make
 * waiting cheap; they do not remove the limit that makes you wait.
 */
@Tag(ProductionLab.TAG)
class HttpDownstreamLimitLabTest {

    private static final Duration SERVICE_TIME = Duration.ofMillis(100);
    private static final int DOWNSTREAM_CAPACITY = 4;
    private static final int REQUESTS = 200;

    private static FakeDownstream downstream;
    private static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll
    static void start() {
        downstream = FakeDownstream.start(SERVICE_TIME);
    }

    @AfterAll
    static void stop() {
        downstream.close();
    }

    @Test
    void moreCallersThanDownstreamCapacityOnlyGrowTheTail() throws Exception {
        Result few = run(DOWNSTREAM_CAPACITY);
        Result many = run(40);

        print("4 callers", few);
        print("40 callers", many);

        assertThat(few.maxInProgress).isLessThanOrEqualTo(DOWNSTREAM_CAPACITY);
        assertThat(many.maxInProgress).isLessThanOrEqualTo(DOWNSTREAM_CAPACITY);   // capacity did not grow
        assertThat(many.maxWaiting).isGreaterThan(20);                              // callers queued instead
        assertThat(few.latencies.p99Ms()).isLessThan(SERVICE_TIME.toMillis() * 3);
        assertThat(many.latencies.p99Ms()).isGreaterThan(SERVICE_TIME.toMillis() * 5);
        // Same throughput: the downstream, not the caller count, decides it (broad bounds: machine-specific).
        assertThat(many.throughput).isLessThan(few.throughput * 1.5);
    }

    private record Result(ProductionLab.Latencies latencies, Timer timer, double throughput, int maxInProgress, int maxWaiting,
                          Duration elapsed) {
    }

    private Result run(int callers) throws Exception {
        downstream.reset(SERVICE_TIME);
        downstream.limitConcurrency(DOWNSTREAM_CAPACITY);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // What the application would export: a client timer with client-side percentiles (a histogram in production).
        Timer timer = Timer.builder("lab.downstream.client").publishPercentiles(0.5, 0.95, 0.99).register(registry);
        ConcurrentLinkedQueue<Long> nanos = new ConcurrentLinkedQueue<>();
        HttpRequest request = HttpRequest.newBuilder(URI.create(downstream.baseUrl() + "/work")).build();

        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> workers = IntStream.range(0, callers)
                    .mapToObj(worker -> executor.submit(() -> {
                        for (int i = worker; i < REQUESTS; i += callers) {
                            long t0 = System.nanoTime();
                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            long took = System.nanoTime() - t0;
                            assertThat(response.statusCode()).isEqualTo(200);
                            nanos.add(took);
                            timer.record(took, TimeUnit.NANOSECONDS);
                        }
                        return null;
                    }))
                    .toList();
            for (Future<Object> worker : workers) {
                worker.get(60, TimeUnit.SECONDS);
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        return new Result(ProductionLab.Latencies.of(nanos), timer, REQUESTS / (elapsed.toNanos() / 1e9),
                downstream.maxInProgress(), downstream.maxWaiting(), elapsed);
    }

    private static void print(String scenario, Result result) {
        StringBuilder micrometer = new StringBuilder();
        for (ValueAtPercentile value : result.timer.takeSnapshot().percentileValues()) {
            micrometer.append(String.format(Locale.ROOT, " p%.0f=%.0f", value.percentile() * 100, value.value(TimeUnit.MILLISECONDS)));
        }
        ProductionLab.print("downstream cap 4 x 100 ms, " + scenario,
                REQUESTS + " requests in " + result.elapsed.toMillis() + " ms, " + String.format(Locale.ROOT, "%.1f req/s", result.throughput)
                        + ", in progress max " + result.maxInProgress + ", waiting max " + result.maxWaiting);
        ProductionLab.print("  latency (exact)", result.latencies.toString());
        ProductionLab.print("  latency (Micrometer timer)", micrometer.toString().trim()
                + String.format(Locale.ROOT, " mean=%.0f max=%.0f ms", result.timer.mean(TimeUnit.MILLISECONDS),
                result.timer.max(TimeUnit.MILLISECONDS)));
    }
}
