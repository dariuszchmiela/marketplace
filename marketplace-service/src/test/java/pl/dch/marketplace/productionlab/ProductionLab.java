package pl.dch.marketplace.productionlab;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

import javax.management.ObjectName;

/**
 * Lab only: small helpers shared by the production/JVM experiments.
 * <ul>
 *   <li>{@link #print}: observations as {@code [PROD-LAB] …} lines in the test output (the numbers are machine-specific
 *       observations, not benchmarks);</li>
 *   <li>{@link Latencies}: exact p50/p95/p99 from recorded samples;</li>
 *   <li>{@link #jcmd}: the same diagnostic commands as the {@code jcmd} tool (Thread.print, GC.class_histogram,
 *       GC.heap_info, ...), invoked in-process through the {@code DiagnosticCommand} MBean so the tests can assert on
 *       their real output;</li>
 *   <li>{@link #holdForManualInspection}: with {@code -Dlab.hold=60s} an experiment keeps its state (blocked threads,
 *       retained heap) long enough to attach {@code jcmd}/JFR from another terminal.</li>
 * </ul>
 */
final class ProductionLab {

    static final String TAG = "production-lab";

    private ProductionLab() {
    }

    static void print(String experiment, String observation) {
        System.out.printf("[PROD-LAB] %-44s %s%n", experiment, observation);
    }

    static long pid() {
        return ProcessHandle.current().pid();
    }

    /** Runs a diagnostic command like {@code jcmd <pid> <command>}, e.g. "threadPrint", "gcClassHistogram". */
    static String jcmd(String operation, String... arguments) {
        try {
            ObjectName diagnosticCommand = new ObjectName("com.sun.management:type=DiagnosticCommand");
            Object[] params = {arguments};
            String[] signature = {String[].class.getName()};
            return (String) ManagementFactory.getPlatformMBeanServer().invoke(diagnosticCommand, operation, params, signature);
        } catch (Exception ex) {
            throw new IllegalStateException("Diagnostic command " + operation + " failed", ex);
        }
    }

    /** Heap in use after a (requested) full GC: the retained, reachable part — not garbage waiting to be collected. */
    static long heapUsedAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            sleep(Duration.ofMillis(100));
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap.getUsed();
    }

    static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    /**
     * {@code -Dlab.hold=60s}: keep the experiment's state alive and print the PID, e.g. for
     * {@code jcmd <pid> Thread.print} or {@code jcmd <pid> GC.class_histogram} in another terminal. No-op by default.
     */
    static void holdForManualInspection(String what) {
        String hold = System.getProperty("lab.hold");
        if (hold == null || hold.isBlank()) {
            return;
        }
        Duration duration = Duration.parse("PT" + hold.toUpperCase());
        print("HOLD", what + " — pid " + pid() + ", holding " + duration.toSeconds() + " s for jcmd/JFR");
        sleep(duration);
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** Exact percentiles (nearest-rank) of recorded latencies. */
    record Latencies(long count, double minMs, double p50Ms, double p95Ms, double p99Ms, double maxMs, double meanMs) {

        static Latencies of(Collection<Long> nanos) {
            long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            if (sorted.length == 0) {
                return new Latencies(0, 0, 0, 0, 0, 0, 0);
            }
            return new Latencies(sorted.length, ms(sorted[0]), ms(percentile(sorted, 50)), ms(percentile(sorted, 95)),
                    ms(percentile(sorted, 99)), ms(sorted[sorted.length - 1]), ms((long) Arrays.stream(sorted).average().orElse(0)));
        }

        private static long percentile(long[] sorted, double percentile) {
            int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
            return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
        }

        private static double ms(long nanos) {
            return nanos / 1_000_000.0;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "n=%d min=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f mean=%.1f ms",
                    count, minMs, p50Ms, p95Ms, p99Ms, maxMs, meanMs);
        }
    }
}
