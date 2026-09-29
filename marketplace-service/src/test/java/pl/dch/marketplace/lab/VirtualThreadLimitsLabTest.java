package pl.dch.marketplace.lab;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: what virtual threads do <em>not</em> change. They make waiting cheap; they do not make the thing
 * you wait for (a downstream with limited capacity, a lock, the CPU) any bigger.
 */
class VirtualThreadLimitsLabTest {

    private static final Duration LATENCY = Duration.ofMillis(200);

    private static FakeDownstream downstream;
    private static DownstreamClient client;

    @BeforeAll
    static void start() {
        downstream = FakeDownstream.start(LATENCY);
        client = new DownstreamClient(downstream.baseUrl(), Duration.ofSeconds(10));
        client.get("/warm-up");
    }

    @AfterEach
    void resetDownstream() {
        downstream.reset(LATENCY);
    }

    @AfterAll
    static void stop() {
        downstream.close();
    }

    @Test
    void fiftyVirtualThreadsAgainstADownstreamLimitedToFiveStillProgressFiveAtATime() throws Exception {
        downstream.limitConcurrency(5);

        Batch limited = callDownstream(50);

        LabReport.print("50 virtual threads -> downstream limit 5",
                limited.elapsedMillis() + " ms, client in flight max " + limited.maxInFlight()
                        + ", downstream processing max " + downstream.maxInProgress()
                        + ", waiting at downstream max " + downstream.maxWaiting());

        // All 50 calls were in flight at once on the client side (virtual threads are cheap)...
        assertThat(limited.maxInFlight()).isEqualTo(50);
        // ...but the downstream processed at most 5 at a time, so 50 / 5 = 10 rounds of 200 ms.
        assertThat(downstream.maxInProgress()).isEqualTo(5);
        assertThat(downstream.maxWaiting()).isGreaterThanOrEqualTo(30);
        assertThat(limited.elapsedMillis()).isBetween(10 * LATENCY.toMillis(), 10 * LATENCY.toMillis() + 1500);
    }

    @Test
    void theSameFiftyCallsWithoutADownstreamLimitFinishInAboutOneLatency() throws Exception {
        Batch unlimited = callDownstream(50);

        LabReport.print("50 virtual threads -> unlimited downstream",
                unlimited.elapsedMillis() + " ms, downstream processing max " + downstream.maxInProgress());

        assertThat(downstream.maxInProgress()).isGreaterThan(5);
        assertThat(unlimited.elapsedMillis()).isLessThan(5 * LATENCY.toMillis());
    }

    @Test
    void tenThousandVirtualThreadsWaitingAtOnceAreCheap() throws Exception {
        int threads = 10_000;
        AtomicInteger finished = new AtomicInteger();
        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    Thread.sleep(LATENCY);
                    return finished.incrementAndGet();
                });
            }
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        LabReport.print("10 000 virtual threads x 200 ms sleep", elapsed + " ms");
        assertThat(finished.get()).isEqualTo(threads);
        assertThat(elapsed).isLessThan(5_000);
    }

    @Test
    void aLockStillSerializesVirtualThreads() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        int tasks = 20;
        Duration criticalSection = Duration.ofMillis(20);
        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                executor.submit(() -> {
                    lock.lock();
                    try {
                        Thread.sleep(criticalSection);
                    } finally {
                        lock.unlock();
                    }
                    return null;
                });
            }
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        LabReport.print("20 virtual threads, one lock, 20 ms each", elapsed + " ms");
        assertThat(elapsed).isGreaterThanOrEqualTo(tasks * criticalSection.toMillis());
    }

    @Test
    void cpuBoundWorkIsNotFasterOnVirtualThreads() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        int tasks = cores * 2;
        runCpuTasks(Executors.newFixedThreadPool(cores), tasks);   // warm-up (JIT)

        long platform = runCpuTasks(Executors.newFixedThreadPool(cores), tasks);
        long virtual = runCpuTasks(Executors.newVirtualThreadPerTaskExecutor(), tasks);

        LabReport.print("CPU-bound, " + tasks + " tasks, " + cores + " cores",
                "fixed pool (cores) " + platform + " ms, virtual threads " + virtual + " ms");
        // Same number of cores do the work; there is nothing to wait for, so nothing to gain.
        assertThat(virtual).isGreaterThan(platform / 2);
    }

    private record Batch(long elapsedMillis, int maxInFlight) {
    }

    private static Batch callDownstream(int calls) throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                int id = i;
                futures.add(executor.submit(() -> {
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        return client.get("/work/" + id);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }));
            }
            for (Future<String> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).startsWith("value-of:/work/");
            }
        }
        return new Batch(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), maxInFlight.get());
    }

    private static long runCpuTasks(ExecutorService executor, int tasks) throws Exception {
        long start = System.nanoTime();
        try (executor) {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(executor.submit(VirtualThreadLimitsLabTest::burnCpu));
            }
            for (Future<Long> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** Pure computation, no I/O: a fixed amount of work per task. */
    private static long burnCpu() {
        long x = 0;
        for (int i = 0; i < 150_000_000; i++) {
            x += (i * 31L) ^ (x >>> 3);
        }
        return x;
    }
}
