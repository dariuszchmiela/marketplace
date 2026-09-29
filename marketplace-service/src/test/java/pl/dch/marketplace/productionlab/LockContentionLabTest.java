package pl.dch.marketplace.productionlab;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: lock contention as it appears in a thread dump. All states are real JVM thread states:
 * <pre>
 * lab-lock-holder      WAITING (parking)            - locked &lt;0x…&gt; (a …InventoryLock)     ← holds the monitor
 * lab-blocked-1..8     BLOCKED (on object monitor)  - waiting to lock &lt;0x…&gt; (same object) ← the symptom
 * lab-parked-1..2      WAITING (parking)            - parking to wait for &lt;0x…&gt; (a ReentrantLock$NonfairSync)
 * lab-awaiting-signal  WAITING (on object monitor)  Object.wait()
 * lab-sleeping         TIMED_WAITING (sleeping)     Thread.sleep()
 * </pre>
 * The same picture comes from {@code jcmd <pid> Thread.print} or {@code jstack <pid>} while the experiment holds
 * ({@code -Dlab.hold=60s}). Diagnosis: many threads BLOCKED on the same address → find the one thread that
 * {@code - locked} it and read <em>its</em> stack: that is the slow critical section.
 */
@Tag(ProductionLab.TAG)
class LockContentionLabTest {

    private static final int BLOCKED_THREADS = 8;

    /** A named class, so the monitor is recognisable in the dump. */
    static final class InventoryLock {
    }

    @Test
    void oneSlowCriticalSectionBlocksEveryoneElse() throws Exception {
        InventoryLock monitor = new InventoryLock();
        ReentrantLock reentrantLock = new ReentrantLock();
        Object signal = new Object();
        CountDownLatch holding = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        threads.add(Thread.ofPlatform().name("lab-lock-holder").start(() -> {
            synchronized (monitor) {
                holding.countDown();
                awaitQuietly(release);   // "slow critical section": holds the monitor while waiting (TIMED_WAITING/WAITING)
            }
        }));
        threads.add(Thread.ofPlatform().name("lab-reentrant-holder").start(() -> {
            reentrantLock.lock();
            try {
                holding.countDown();
                awaitQuietly(release);
            } finally {
                reentrantLock.unlock();
            }
        }));
        holding.await();
        for (int i = 1; i <= BLOCKED_THREADS; i++) {
            threads.add(Thread.ofPlatform().name("lab-blocked-" + i).start(() -> {
                synchronized (monitor) {
                    Thread.onSpinWait();
                }
            }));
        }
        for (int i = 1; i <= 2; i++) {
            threads.add(Thread.ofPlatform().name("lab-parked-" + i).start(() -> {
                reentrantLock.lock();
                reentrantLock.unlock();
            }));
        }
        threads.add(Thread.ofPlatform().name("lab-awaiting-signal").start(() -> {
            synchronized (signal) {
                try {
                    signal.wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }));
        threads.add(Thread.ofPlatform().name("lab-sleeping").start(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(120));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();   // released by the test
            }
        }));
        ProductionLab.sleep(Duration.ofMillis(500));   // let everyone reach its waiting point

        try {
            Map<String, Thread.State> states = threads.stream()
                    .collect(Collectors.toMap(Thread::getName, Thread::getState));
            ProductionLab.print("thread states", states.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(", ")));

            assertThat(states).containsEntry("lab-lock-holder", Thread.State.WAITING);   // CountDownLatch.await: parked
            for (int i = 1; i <= BLOCKED_THREADS; i++) {
                assertThat(states).containsEntry("lab-blocked-" + i, Thread.State.BLOCKED);
            }
            assertThat(states).containsEntry("lab-parked-1", Thread.State.WAITING);
            assertThat(states).containsEntry("lab-awaiting-signal", Thread.State.WAITING);
            assertThat(states).containsEntry("lab-sleeping", Thread.State.TIMED_WAITING);

            // ThreadMXBean: who owns the lock everybody is waiting for?
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            ThreadInfo blocked = Arrays.stream(mx.dumpAllThreads(true, true))
                    .filter(info -> info.getThreadName().equals("lab-blocked-1")).findFirst().orElseThrow();
            ProductionLab.print("lab-blocked-1", "state " + blocked.getThreadState() + ", waiting for " + blocked.getLockName()
                    + ", owned by " + blocked.getLockOwnerName() + ", blocked count " + blocked.getBlockedCount());
            assertThat(blocked.getLockOwnerName()).isEqualTo("lab-lock-holder");
            assertThat(blocked.getLockName()).contains("InventoryLock");

            // The same as `jcmd <pid> Thread.print`.
            String dump = ProductionLab.jcmd("threadPrint");
            String holderSection = section(dump, "\"lab-lock-holder\"");
            String blockedSection = section(dump, "\"lab-blocked-1\"");
            String parkedSection = section(dump, "\"lab-parked-1\"");
            ProductionLab.print("Thread.print (holder)", firstLines(holderSection, 12));
            ProductionLab.print("Thread.print (blocked)", firstLines(blockedSection, 5));
            ProductionLab.print("Thread.print (parked)", firstLines(parkedSection, 6));
            assertThat(blockedSection).contains("java.lang.Thread.State: BLOCKED (on object monitor)")
                    .contains("- waiting to lock <").contains("InventoryLock");
            assertThat(holderSection).contains("- locked <").contains("InventoryLock");
            assertThat(parkedSection).contains("WAITING (parking)").contains("ReentrantLock");

            ProductionLab.holdForManualInspection("8 BLOCKED + 2 parked + 1 waiting + 1 sleeping threads");
        } finally {
            release.countDown();
            synchronized (signal) {
                signal.notifyAll();
            }
            for (Thread thread : threads) {
                thread.interrupt();
                thread.join(5_000);
            }
        }
    }

    private static String section(String dump, String threadHeader) {
        int start = dump.indexOf(threadHeader);
        assertThat(start).as("thread " + threadHeader + " in dump").isNotNegative();
        int end = dump.indexOf("\n\n", start);
        return end < 0 ? dump.substring(start) : dump.substring(start, end);
    }

    private static String firstLines(String text, int lines) {
        return "\n    " + text.lines().limit(lines).collect(Collectors.joining("\n    "));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
