import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Standalone JVM for GC / heap / OOM experiments with explicit JVM flags — never part of any test run. Examples
 * (docs/production-diagnostics.md has the full list and the observed output):
 * <pre>
 * # GC log of short-lived allocations (young collections, pause times, heap before/after):
 * java -Xmx256m -Xlog:gc:stdout tools/production-lab/MemoryDemo.java churn 5
 * java -Xmx256m "-Xlog:gc*:file=target/gc.log:time,uptime,level,tags" tools/production-lab/MemoryDemo.java churn 5
 *
 * # Retention until OutOfMemoryError, with an automatic heap dump (tiny heap, so it happens within seconds):
 * java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=target/oom.hprof tools/production-lab/MemoryDemo.java retain
 *
 * # Keep a JVM with 100 MiB retained alive for jcmd (GC.heap_info, GC.class_histogram, JFR.start/dump/stop):
 * java -Xmx512m tools/production-lab/MemoryDemo.java hold 100 300
 * </pre>
 * .hprof and .jfr files are ignored by git: a heap dump contains every object in memory (tokens, personal data).
 */
public class MemoryDemo {

    private static final List<byte[]> RETAINED = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "churn";
        System.out.printf("MemoryDemo %s - pid %d, max heap %d MiB%n", mode, ProcessHandle.current().pid(),
                Runtime.getRuntime().maxMemory() / 1024 / 1024);
        switch (mode) {
            case "churn" -> churn(Duration.ofSeconds(args.length > 1 ? Long.parseLong(args[1]) : 5));
            case "retain" -> retainUntilOutOfMemory();
            case "hold" -> hold(Integer.parseInt(args.length > 1 ? args[1] : "100"),
                    Duration.ofSeconds(args.length > 2 ? Long.parseLong(args[2]) : 300));
            default -> throw new IllegalArgumentException("mode: churn [seconds] | retain | hold [MiB] [seconds]");
        }
    }

    /** Short-lived garbage only: GC pressure without a leak — heap after each young GC stays flat. */
    private static void churn(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        Object[] ring = new Object[4096];
        long allocated = 0;
        int i = 0;
        while (System.nanoTime() < deadline) {
            byte[] buffer = new byte[512 + (i & 511)];
            ring[i++ & 4095] = buffer;
            allocated += buffer.length + 16;
        }
        long gcCount = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> gc.getCollectionCount()).sum();
        long gcMillis = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> gc.getCollectionTime()).sum();
        System.out.printf(Locale.ROOT, "allocated ~%d MiB in %d s (%.0f MiB/s), %d collections, %d ms total GC time%n",
                allocated / 1024 / 1024, duration.toSeconds(), allocated / 1024.0 / 1024 / duration.toSeconds(), gcCount, gcMillis);
    }

    /** A leak by construction: every chunk stays reachable, so the GC cannot help; ends in OutOfMemoryError. */
    private static void retainUntilOutOfMemory() {
        try {
            while (true) {
                RETAINED.add(new byte[1024 * 1024]);
                if (RETAINED.size() % 8 == 0) {
                    System.out.printf("retained %d MiB%n", RETAINED.size());
                }
            }
        } catch (OutOfMemoryError error) {
            int retained = RETAINED.size();
            RETAINED.clear();   // free memory so the JVM can report and exit cleanly
            System.out.printf("OutOfMemoryError after retaining %d MiB: %s%n", retained, error.getMessage());
        }
    }

    private static void hold(int mib, Duration duration) throws InterruptedException {
        for (int i = 0; i < mib; i++) {
            RETAINED.add(new byte[1024 * 1024]);
        }
        System.out.printf("retaining %d MiB for %d s - try: jcmd %d GC.class_histogram%n", mib, duration.toSeconds(),
                ProcessHandle.current().pid());
        Thread.sleep(duration);
    }
}
