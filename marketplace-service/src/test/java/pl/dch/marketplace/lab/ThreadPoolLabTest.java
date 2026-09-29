package pl.dch.marketplace.lab;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: 20 blocking tasks of 200 ms (a sleep stands for blocking I/O) on a fixed pool of 4 platform threads
 * versus one virtual thread per task. Only this test creates these executors; the application's
 * executors are not touched.
 */
class ThreadPoolLabTest {

    private static final int POOL_SIZE = 4;
    private static final int TASKS = 20;
    private static final Duration TASK_DURATION = Duration.ofMillis(200);

    @Test
    void fixedPoolRunsAtMostPoolSizeTasksAndQueuesTheRestInBatches() throws Exception {
        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(POOL_SIZE,
                Thread.ofPlatform().name("lab-fixed-", 1).factory());
        try {
            Run run = runBlockingTasks(pool);
            int queuedRightAfterSubmit = run.queuedAfterSubmit();

            LabReport.print("fixed pool 4, 20 x 200 ms blocking",
                    run.elapsedMillis() + " ms, max concurrent " + run.maxConcurrent()
                            + ", queued after submit " + queuedRightAfterSubmit);

            assertThat(run.maxConcurrent()).isEqualTo(POOL_SIZE);
            // 20 tasks / 4 threads = 5 rounds of 200 ms.
            assertThat(run.elapsedMillis()).isBetween(5 * TASK_DURATION.toMillis(), 5 * TASK_DURATION.toMillis() + 600);
            assertThat(queuedRightAfterSubmit).isGreaterThanOrEqualTo(TASKS - POOL_SIZE - 1);
            // Task i can only start in round i / 4: waiting in the queue is visible as a late start.
            List<Long> starts = run.startOffsetsMillis().stream().sorted().toList();
            for (int i = 0; i < TASKS; i++) {
                long round = i / POOL_SIZE;
                assertThat(starts.get(i)).isGreaterThanOrEqualTo(round * TASK_DURATION.toMillis() - 50);
            }
        } finally {
            shutdown(pool);
        }
    }

    @Test
    void virtualThreadPerTaskStartsEveryTaskImmediately() throws Exception {
        ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Run run = runBlockingTasks(virtualThreads);

            LabReport.print("virtual thread per task, 20 x 200 ms blocking",
                    run.elapsedMillis() + " ms, max concurrent " + run.maxConcurrent());

            assertThat(run.maxConcurrent()).isEqualTo(TASKS);
            assertThat(run.elapsedMillis()).isBetween(TASK_DURATION.toMillis(), 2 * TASK_DURATION.toMillis());
        } finally {
            shutdown(virtualThreads);
        }
    }

    private record Run(long elapsedMillis, int maxConcurrent, int queuedAfterSubmit, List<Long> startOffsetsMillis) {
    }

    private static Run runBlockingTasks(ExecutorService executor) throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        List<Long> startOffsets = java.util.Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < TASKS; i++) {
            futures.add(executor.submit(() -> {
                startOffsets.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(TASK_DURATION);   // blocking I/O stand-in
                } finally {
                    running.decrementAndGet();
                }
                return null;
            }));
        }
        int queued = executor instanceof ThreadPoolExecutor pool ? pool.getQueue().size() : 0;
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new Run(elapsed, maxRunning.get(), queued, List.copyOf(startOffsets));
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
