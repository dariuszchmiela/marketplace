package pl.dch.marketplace.lab;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.dch.marketplace.lab.DownstreamClient.DownstreamException;
import pl.dch.marketplace.lab.LabReport.Timed;
import pl.dch.marketplace.lab.ProductPageLoader.Part;
import pl.dch.marketplace.lab.ProductPageLoader.ProductPage;
import pl.dch.marketplace.lab.ProductPageLoader.ProductPageTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: three independent 300 ms blocking calls, done sequentially, with {@code CompletableFuture} on an
 * explicit pool, and with virtual threads. Bounds are deliberately broad (CI machines vary); the point is
 * the shape: sequential ≈ sum, concurrent ≈ slowest call, and virtual threads are not faster than the
 * remote service they wait for.
 */
class ParallelCallsLabTest {

    private static final Duration LATENCY = Duration.ofMillis(300);
    private static final long SUM = 3 * LATENCY.toMillis();

    private static FakeDownstream downstream;
    private static ExecutorService ioExecutor;
    private static ProductPageLoader loader;

    @BeforeAll
    static void start() {
        downstream = FakeDownstream.start(LATENCY);
        ioExecutor = ProductPageLoader.newIoExecutor(8);
        loader = new ProductPageLoader(new DownstreamClient(downstream.baseUrl(), Duration.ofSeconds(5)));
        // Warm-up (class loading, JIT, first connections), so the measured runs compare like with like.
        loader.loadSequentially(0);
        loader.loadWithCompletableFuture(0, ioExecutor, Duration.ofSeconds(5));
        loader.loadWithVirtualThreads(0);
    }

    @AfterEach
    void resetDownstream() {
        downstream.reset(LATENCY);
    }

    @AfterAll
    static void stop() throws InterruptedException {
        ioExecutor.shutdown();
        assertThat(ioExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        downstream.close();
    }

    @Test
    void sameWorkloadSequentialVersusCompletableFutureVersusVirtualThreads() {
        Timed<ProductPage> sequential = LabReport.time(() -> loader.loadSequentially(1));
        Timed<ProductPage> completableFuture = LabReport.time(() -> loader.loadWithCompletableFuture(1, ioExecutor, Duration.ofSeconds(5)));
        Timed<ProductPage> virtualThreads = LabReport.time(() -> loader.loadWithVirtualThreads(1));

        LabReport.print("3 x 300 ms calls: sequential", sequential.millis() + " ms");
        LabReport.print("3 x 300 ms calls: CompletableFuture (io pool)", completableFuture.millis() + " ms");
        LabReport.print("3 x 300 ms calls: virtual threads", virtualThreads.millis() + " ms");

        // Same results from all three.
        assertThat(completableFuture.result().parts()).extracting(Part::value)
                .isEqualTo(sequential.result().parts().stream().map(Part::value).toList())
                .isEqualTo(virtualThreads.result().parts().stream().map(Part::value).toList());

        // Sequential waits for every call in turn.
        assertThat(sequential.millis()).isGreaterThanOrEqualTo(SUM);
        // Concurrent variants wait for the slowest call only - but never less than it.
        assertThat(completableFuture.millis()).isBetween(LATENCY.toMillis(), 2 * LATENCY.toMillis());
        assertThat(virtualThreads.millis()).isBetween(LATENCY.toMillis(), 2 * LATENCY.toMillis());
        assertThat(sequential.millis()).isGreaterThan(2 * Math.max(completableFuture.millis(), virtualThreads.millis()));
    }

    @Test
    void completableFutureRunsOnTheExplicitPoolNeverOnTheCommonPool() {
        ProductPage page = loader.loadWithCompletableFuture(2, ioExecutor, Duration.ofSeconds(5));

        assertThat(page.parts()).allSatisfy(part -> {
            assertThat(part.threadName()).startsWith("product-page-io-").doesNotContain("ForkJoinPool");
            assertThat(part.virtual()).isFalse();
        });
        assertThat(page.parts()).extracting(Part::threadName).doesNotHaveDuplicates();
    }

    @Test
    void virtualThreadVariantRunsEachCallOnItsOwnVirtualThread() {
        ProductPage page = loader.loadWithVirtualThreads(3);

        assertThat(page.parts()).allSatisfy(part -> assertThat(part.virtual()).isTrue());
    }

    @Test
    void sequentialVariantBlocksTheCallersThread() {
        ProductPage page = loader.loadSequentially(4);

        assertThat(page.parts()).extracting(Part::threadName).containsOnly(Thread.currentThread().getName());
    }

    @Test
    void completableFutureFailsFastWhenOneCallFails() {
        downstream.fail("/stock");

        Timed<Throwable> failure = LabReport.time(() -> catchFailure(() -> loader.loadWithCompletableFuture(5, ioExecutor, Duration.ofSeconds(5))));

        LabReport.print("CompletableFuture, /stock fails at once", "failed after " + failure.millis() + " ms (fail-fast)");
        assertThat(failure.result()).isInstanceOf(DownstreamException.class)
                .satisfies(ex -> assertThat(((DownstreamException) ex).path()).isEqualTo("/stock/5"));
        // Did not wait for the two 300 ms siblings.
        assertThat(failure.millis()).isLessThan(LATENCY.toMillis());
    }

    @Test
    void virtualThreadVariantReportsTheFailureAfterItsSiblingsFinished() {
        downstream.fail("/stock");

        Timed<Throwable> failure = LabReport.time(() -> catchFailure(() -> loader.loadWithVirtualThreads(6)));

        LabReport.print("virtual threads, /stock fails at once", "failed after " + failure.millis() + " ms (waits for siblings)");
        assertThat(failure.result()).isInstanceOf(DownstreamException.class);
        // Structured: close() waited for the price call (300 ms) before the failure left the method.
        assertThat(failure.millis()).isGreaterThanOrEqualTo(LATENCY.toMillis());
    }

    @Test
    void completableFutureStopsWaitingAtItsTimeout() {
        downstream.latency("/delivery", Duration.ofSeconds(2));

        Timed<Throwable> failure = LabReport.time(() -> catchFailure(() -> loader.loadWithCompletableFuture(7, ioExecutor, Duration.ofMillis(500))));

        LabReport.print("CompletableFuture, /delivery 2 s, timeout 500 ms", "failed after " + failure.millis() + " ms");
        assertThat(failure.result()).isInstanceOf(ProductPageTimeoutException.class);
        assertThat(failure.millis()).isBetween(500L, 1500L);
    }

    private static Throwable catchFailure(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException ex) {
            return ex;
        }
        throw new AssertionError("expected a failure");
    }
}
