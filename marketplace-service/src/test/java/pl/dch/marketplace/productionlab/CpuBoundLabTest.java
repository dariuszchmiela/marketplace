package pl.dch.marketplace.productionlab;

import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: CPU-bound work (SHA-256 rounds, no I/O, no waiting). 4 × cores tasks run on
 * <ul>
 *   <li>a fixed pool of {@code availableProcessors} platform threads, and</li>
 *   <li>a virtual-thread-per-task executor.</li>
 * </ul>
 * Both take about the same wall-clock time and the same CPU time: the carriers are a ForkJoinPool with one thread
 * per core, and a virtual thread that never blocks never yields its carrier. Virtual threads are a tool for
 * <em>waiting</em> cheaply (I/O-bound work), not a CPU optimisation — there is no extra core to schedule them on.
 */
@Tag(ProductionLab.TAG)
class CpuBoundLabTest {

    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final int TASKS = CORES * 4;
    private static final int ROUNDS = 1_000_000;   // ~0.1-0.3 s of hashing per task on a laptop core

    @Test
    void virtualThreadsDoNotCreateCpuCapacity() throws Exception {
        // JIT warm-up (a full unmeasured round), so the first measured variant is not penalised by interpretation.
        measure("warm-up (not compared)", Executors.newFixedThreadPool(CORES));
        Measurement fixed = measure("fixed pool (" + CORES + " platform threads)", Executors.newFixedThreadPool(CORES));
        Measurement virtual = measure("virtual thread per task", Executors.newVirtualThreadPerTaskExecutor());

        double ratio = (double) virtual.wall.toMillis() / fixed.wall.toMillis();
        ProductionLab.print("virtual / fixed wall-clock", String.format(Locale.ROOT, "%.2f (~1.0 expected)", ratio));

        assertThat(ratio).isBetween(0.6, 1.6);
        // CPU time is the work itself; it does not shrink with another scheduling model.
        assertThat((double) virtual.cpu.toMillis() / fixed.cpu.toMillis()).isBetween(0.5, 2.0);
    }

    private record Measurement(Duration wall, Duration cpu) {
    }

    private Measurement measure(String name, ExecutorService executor) throws Exception {
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuBefore = os.getProcessCpuTime();
        long start = System.nanoTime();
        try (executor) {
            List<Future<byte[]>> results = new ArrayList<>();
            for (int i = 0; i < TASKS; i++) {
                results.add(executor.submit(CpuBoundLabTest::work));
            }
            for (Future<byte[]> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS)).hasSize(32);
            }
        }
        Duration wall = Duration.ofNanos(System.nanoTime() - start);
        Duration cpu = Duration.ofNanos(os.getProcessCpuTime() - cpuBefore);
        ProductionLab.print(name, String.format(Locale.ROOT,
                "%d tasks on %d cores: wall %d ms, process CPU time %d ms = %.1f cores busy on average (%.0f%% of the machine), "
                        + "system CPU load at the end %.0f%%", TASKS, CORES, wall.toMillis(), cpu.toMillis(),
                (double) cpu.toNanos() / wall.toNanos(), 100.0 * cpu.toNanos() / wall.toNanos() / CORES, os.getCpuLoad() * 100));
        return new Measurement(wall, cpu);
    }

    private static byte[] work() throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] value = new byte[32];
        for (int i = 0; i < ROUNDS; i++) {
            value = sha256.digest(value);
        }
        return value;
    }
}
