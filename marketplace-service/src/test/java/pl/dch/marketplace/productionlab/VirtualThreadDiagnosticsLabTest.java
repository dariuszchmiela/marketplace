package pl.dch.marketplace.productionlab;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: what virtual threads change — and what they don't.
 * <ul>
 *   <li>10,000 waiting virtual threads are cheap: the JVM's <em>platform</em> thread count barely moves (they are
 *       parked continuations on the heap, mounted on a few carrier threads only while running);</li>
 *   <li>the classic thread dump ({@code jcmd Thread.print}) shows platform threads and carriers — not the 10,000
 *       parked virtual threads. The Java 21+ dump {@code jcmd <pid> Thread.dump_to_file -format=json <file>} lists
 *       them all;</li>
 *   <li>a capacity limit (here a {@link Semaphore} of 10, like a DB pool) is unchanged: 1,000 tasks × 20 ms through 10
 *       permits still take ≈ 1000/10 × 20 ms = 2 s, however many virtual threads wait.</li>
 * </ul>
 */
@Tag(ProductionLab.TAG)
class VirtualThreadDiagnosticsLabTest {

    private static final int VIRTUAL_THREADS = 10_000;

    @Test
    void thousandsOfWaitingVirtualThreadsAreCheapButInvisibleToTheClassicDump() throws Exception {
        var threadMx = ManagementFactory.getThreadMXBean();
        int platformBefore = threadMx.getThreadCount();
        long heapBefore = ProductionLab.heapUsedAfterGc();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allWaiting = new CountDownLatch(VIRTUAL_THREADS);

        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < VIRTUAL_THREADS; i++) {
                executor.submit(() -> {
                    allWaiting.countDown();
                    release.await();   // parked: unmounted from its carrier
                    return null;
                });
            }
            allWaiting.await();
            Duration startup = Duration.ofNanos(System.nanoTime() - start);
            int platformDuring = threadMx.getThreadCount();
            long heapDuring = ProductionLab.heapUsedAfterGc();

            String classicDump = ProductionLab.jcmd("threadPrint");
            Path jsonDump = Path.of("target", "production-lab", "virtual-threads-" + ProductionLab.pid() + ".json");
            Files.createDirectories(jsonDump.getParent());
            Files.deleteIfExists(jsonDump);
            ProductionLab.jcmd("threadDumpToFile", "-format=json", jsonDump.toAbsolutePath().toString());
            String json = Files.readString(jsonDump);
            int virtualInJson = count(json, "\"virtual\": true") + count(json, "\"virtual\":true");
            int carriers = count(classicDump, "\"ForkJoinPool-1-worker-");

            ProductionLab.print(VIRTUAL_THREADS + " parked virtual threads",
                    "started in " + startup.toMillis() + " ms; platform threads " + platformBefore + " -> " + platformDuring
                            + "; heap after GC " + ProductionLab.mib(heapBefore) + " -> " + ProductionLab.mib(heapDuring)
                            + " (~" + (heapDuring - heapBefore) / VIRTUAL_THREADS + " bytes/thread)");
            ProductionLab.print("classic Thread.print", "virtual-thread carriers listed: " + carriers
                    + ", mentions of our 10,000 virtual threads: none (dump size " + classicDump.length() / 1024 + " KiB)");
            ProductionLab.print("Thread.dump_to_file -format=json", jsonDump + " (" + Files.size(jsonDump) / 1024
                    + " KiB), virtual threads listed: " + virtualInJson);
            ProductionLab.holdForManualInspection(VIRTUAL_THREADS + " parked virtual threads");

            assertThat(platformDuring - platformBefore).isLessThan(64);   // not 10,000 OS threads
            assertThat(virtualInJson).isGreaterThanOrEqualTo(VIRTUAL_THREADS);
            release.countDown();
        }
    }

    @Test
    void virtualThreadsDoNotAddCapacityToALimitedResource() throws Exception {
        Semaphore connections = new Semaphore(10);   // stands in for a pool of 10 DB connections
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger current = new AtomicInteger();
        int tasks = 1_000;
        Duration hold = Duration.ofMillis(20);

        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                executor.submit(() -> {
                    connections.acquire();
                    try {
                        maxConcurrent.accumulateAndGet(current.incrementAndGet(), Math::max);
                        Thread.sleep(hold);
                    } finally {
                        current.decrementAndGet();
                        connections.release();
                    }
                    return null;
                });
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        ProductionLab.print("1000 virtual threads x 20 ms, 10 permits",
                "elapsed " + elapsed.toMillis() + " ms (theory " + tasks / 10 * hold.toMillis() + " ms), max concurrent "
                        + maxConcurrent.get());

        assertThat(maxConcurrent.get()).isEqualTo(10);
        assertThat(elapsed).isGreaterThanOrEqualTo(hold.multipliedBy(tasks / 10).minusMillis(100));
    }

    private static int count(String text, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
