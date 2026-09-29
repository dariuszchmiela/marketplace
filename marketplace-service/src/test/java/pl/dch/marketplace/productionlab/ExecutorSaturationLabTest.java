package pl.dch.marketplace.productionlab;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: 4 worker threads, 200 tasks of 20 ms submitted at once (a burst ~6× larger than the pool can absorb quickly).
 * Observed through Micrometer's {@link ExecutorServiceMetrics} — the same {@code executor_active_threads},
 * {@code executor_queued_tasks}, {@code executor_completed_tasks_total}, {@code executor_pool_size_threads} an application
 * executor would export.
 * <ul>
 *   <li><b>unbounded queue</b> ({@code Executors.newFixedThreadPool}): never rejects, so the overload is invisible —
 *       except as queue depth and waiting time. The last task waits ~1 s; with a steady overload the queue (and heap,
 *       and latency) grows without limit;</li>
 *   <li><b>bounded queue + AbortPolicy</b>: explicit failure — the caller learns immediately and can shed load
 *       (e.g. 503 + Retry-After) instead of accepting work it will finish too late;</li>
 *   <li><b>bounded queue + CallerRunsPolicy</b>: backpressure — the submitting thread runs the task itself and so
 *       slows down its own intake. No task is lost, the producer is throttled.</li>
 * </ul>
 * No application executor is changed by this lab.
 */
@Tag(ProductionLab.TAG)
class ExecutorSaturationLabTest {

    private static final int THREADS = 4;
    private static final int TASKS = 200;
    private static final Duration TASK_TIME = Duration.ofMillis(20);

    @Test
    void unboundedQueueHidesTheOverloadAsWaitingTime() throws Exception {
        Observed observed = run("unbounded", new LinkedBlockingQueue<>(), new ThreadPoolExecutor.AbortPolicy());

        assertThat(observed.rejected).isZero();
        assertThat(observed.completed).isEqualTo(TASKS);
        assertThat(observed.maxActive).isEqualTo(THREADS);
        assertThat(observed.maxQueued).isGreaterThan(TASKS - THREADS - 10);
        assertThat(observed.maxWait).isGreaterThan(TASK_TIME.multipliedBy(TASKS / THREADS / 2));
    }

    @Test
    void boundedQueueWithAbortPolicyRejectsTheExcessExplicitly() throws Exception {
        Observed observed = run("bounded(20)+Abort", new ArrayBlockingQueue<>(20), new ThreadPoolExecutor.AbortPolicy());

        assertThat(observed.rejected).isGreaterThan(TASKS / 2);
        assertThat(observed.completed + observed.rejected).isEqualTo(TASKS);
        assertThat(observed.maxQueued).isLessThanOrEqualTo(20);
        assertThat(observed.maxWait).isLessThan(TASK_TIME.multipliedBy(20 / THREADS + 5));
    }

    @Test
    void boundedQueueWithCallerRunsPolicyThrottlesTheProducer() throws Exception {
        Observed observed = run("bounded(20)+CallerRuns", new ArrayBlockingQueue<>(20), new ThreadPoolExecutor.CallerRunsPolicy());

        assertThat(observed.rejected).isZero();
        assertThat(observed.completed + observed.ranByCaller).isEqualTo(TASKS);
        assertThat(observed.ranByCaller).isPositive();
        assertThat(observed.maxQueued).isLessThanOrEqualTo(20);
    }

    private record Observed(int completed, int rejected, int ranByCaller, int maxActive, int maxQueued, Duration maxWait,
                            Duration submitTime, Duration elapsed) {
    }

    private Observed run(String name, BlockingQueue<Runnable> queue, RejectedExecutionHandler policy) throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(THREADS, THREADS, 0, TimeUnit.MILLISECONDS, queue,
                Thread.ofPlatform().name("lab-worker-", 0).factory(), policy);
        ExecutorServiceMetrics.monitor(registry, executor, "lab", Tags.of("policy", name));
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger ranByCaller = new AtomicInteger();
        ConcurrentLinkedQueue<Long> waits = new ConcurrentLinkedQueue<>();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger maxQueued = new AtomicInteger();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread submitter = Thread.currentThread();

        Thread sampler = Thread.ofPlatform().start(() -> {
            while (sampling.get()) {
                maxActive.accumulateAndGet((int) registry.get("executor.active").gauge().value(), Math::max);
                maxQueued.accumulateAndGet((int) registry.get("executor.queued").gauge().value(), Math::max);
                ProductionLab.sleep(Duration.ofMillis(2));
            }
        });
        long start = System.nanoTime();
        for (int i = 0; i < TASKS; i++) {
            long submitted = System.nanoTime();
            try {
                executor.execute(() -> {
                    waits.add(System.nanoTime() - submitted);
                    if (Thread.currentThread() == submitter) {
                        ranByCaller.incrementAndGet();
                    }
                    ProductionLab.sleep(TASK_TIME);
                });
            } catch (RejectedExecutionException ex) {
                rejected.incrementAndGet();
            }
        }
        Duration submitTime = Duration.ofNanos(System.nanoTime() - start);
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        sampling.set(false);
        sampler.join();

        int completed = (int) registry.get("executor.completed").functionCounter().count();
        Duration maxWait = Duration.ofNanos(waits.stream().mapToLong(Long::longValue).max().orElse(0));
        ProductionLab.print("pool 4, " + TASKS + " x 20 ms, " + name,
                "completed(pool) " + completed + ", ran by caller " + ranByCaller + ", rejected " + rejected
                        + ", max active " + maxActive + ", max queued " + maxQueued + ", max wait " + maxWait.toMillis()
                        + " ms, submit loop " + submitTime.toMillis() + " ms, total " + elapsed.toMillis() + " ms");
        return new Observed(completed, rejected.get(), ranByCaller.get(), maxActive.get(), maxQueued.get(),
                maxWait, submitTime, elapsed);
    }
}
