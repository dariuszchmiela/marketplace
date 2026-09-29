package pl.dch.marketplace.productionlab;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: allocation rate and garbage collection, recorded with Java Flight Recorder.
 * <p>
 * ~2 seconds of short-lived allocations (objects that die young: the common case in request processing). The young
 * generation fills up quickly, so the collector runs many cheap young collections; almost nothing survives, heap
 * occupancy after GC stays flat. That is GC <em>pressure</em> (allocation rate × GC frequency), not a leak.
 * <p>
 * The JFR recording (profile settings) is written to {@code target/production-lab/gc-lab.jfr} and read back with the
 * {@code jdk.jfr.consumer} API — the same data {@code jfr print --events jdk.GarbageCollection} or JDK Mission Control
 * show. The file is under target/ and never committed.
 */
@Tag(ProductionLab.TAG)
class GcAndJfrLabTest {

    @Test
    void shortLivedAllocationsCauseManyCheapYoungCollections() throws Exception {
        Path file = Path.of("target", "production-lab", "gc-lab.jfr");
        Files.createDirectories(file.getParent());
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        Map<String, long[]> before = snapshot(collectors);
        long allocated;

        try (Recording recording = new Recording(jdk.jfr.Configuration.getConfiguration("profile"))) {
            recording.setToDisk(true);
            recording.start();
            allocated = allocateShortLivedObjects(Duration.ofSeconds(2));
            recording.stop();
            recording.dump(file);
        }

        Map<String, long[]> after = snapshot(collectors);
        after.forEach((name, counts) -> ProductionLab.print("GC " + name,
                "collections +" + (counts[0] - before.get(name)[0]) + ", time +" + (counts[1] - before.get(name)[1]) + " ms"));
        ProductionLab.print("allocated (approx.)", ProductionLab.mib(allocated) + " in 2 s = "
                + String.format(Locale.ROOT, "%.0f MiB/s", allocated / 2.0 / 1024 / 1024));

        Map<String, Integer> events = new TreeMap<>();
        long maxPauseMicros = 0;
        long gcCount = 0;
        for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
            String type = event.getEventType().getName();
            events.merge(type, 1, Integer::sum);
            if (type.equals("jdk.GarbageCollection")) {
                gcCount++;
                maxPauseMicros = Math.max(maxPauseMicros, event.getDuration("longestPause").toNanos() / 1000);
            }
        }
        ProductionLab.print("JFR file", file + " (" + Files.size(file) / 1024 + " KiB), " + events.size() + " event types");
        ProductionLab.print("JFR jdk.GarbageCollection", gcCount + " collections, longest pause " + maxPauseMicros + " us");
        for (String interesting : List.of("jdk.GarbageCollection", "jdk.GCHeapSummary", "jdk.ObjectAllocationSample",
                "jdk.ExecutionSample", "jdk.ThreadPark", "jdk.JavaMonitorEnter", "jdk.SocketRead", "jdk.CPULoad",
                "jdk.ThreadCPULoad", "jdk.YoungGarbageCollection", "jdk.G1GarbageCollection")) {
            ProductionLab.print("  " + interesting, String.valueOf(events.getOrDefault(interesting, 0)));
        }

        long youngCollections = after.entrySet().stream()
                .filter(entry -> entry.getKey().contains("Young") || entry.getKey().contains("Scavenge")
                        || entry.getKey().contains("ParNew") || entry.getKey().contains("Copy"))
                .mapToLong(entry -> entry.getValue()[0] - before.get(entry.getKey())[0]).sum();
        assertThat(youngCollections).isGreaterThan(3);
        assertThat(gcCount).isPositive();
        assertThat(events).containsKeys("jdk.GarbageCollection", "jdk.ObjectAllocationSample", "jdk.ExecutionSample");
    }

    private static Map<String, long[]> snapshot(List<GarbageCollectorMXBean> collectors) {
        Map<String, long[]> counts = new TreeMap<>();
        for (GarbageCollectorMXBean collector : collectors) {
            counts.put(collector.getName(), new long[] {collector.getCollectionCount(), collector.getCollectionTime()});
        }
        return counts;
    }

    /** Allocates small arrays and strings that become garbage immediately; returns roughly how many bytes. */
    private static long allocateShortLivedObjects(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        long bytes = 0;
        long checksum = 0;
        // A small ring the objects escape into (so escape analysis cannot remove the allocation); overwritten
        // immediately, so every object dies young.
        Object[] ring = new Object[1024];
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 10_000; i++) {
                byte[] buffer = new byte[256 + ThreadLocalRandom.current().nextInt(512)];
                String text = "order-" + i + "-" + buffer.length;
                ring[i & 1023] = buffer;
                ring[(i + 512) & 1023] = text;
                checksum += buffer.length + text.length();
                bytes += buffer.length + 16 + 40 + text.length();
            }
        }
        assertThat(checksum).isPositive();
        return bytes;
    }
}
