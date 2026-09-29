package pl.dch.marketplace.productionlab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: retained memory — what stays reachable after a full GC — and how to find it with
 * {@code jcmd GC.class_histogram} / {@code GC.heap_info} (run in-process here, from another terminal in real life).
 * <ul>
 *   <li><b>bounded retention</b>: {@code lab.retainMiB} (default 64) × 1 MiB {@code byte[]} held by a static list.
 *       The histogram's top line becomes {@code [B} (byte arrays) with ≥ 64 MiB; releasing the list gives it back;</li>
 *   <li><b>unbounded cache</b>: a {@code ConcurrentHashMap} used as a cache keyed by something unique per request
 *       (request id, session id, a timestamp in the key...) grows with traffic and never shrinks: a leak with a
 *       perfectly innocent-looking API. The bounded variant (LRU with a maximum size) keeps a flat footprint.</li>
 * </ul>
 * Bounded and configurable on purpose: the normal build never runs this, and even here it never approaches OOM.
 * The explicit OOM demo is a separate command (tools/production-lab/MemoryDemo.java, tiny heap).
 */
@Tag(ProductionLab.TAG)
class HeapRetentionLabTest {

    private static final Pattern HISTOGRAM_LINE = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)", Pattern.MULTILINE);

    /** The "leak": reachable from a static field, so the GC must keep it. */
    private static final List<byte[]> RETAINED = new ArrayList<>();

    @AfterEach
    void release() {
        RETAINED.clear();
    }

    @Test
    void retainedByteArraysDominateTheClassHistogram() {
        int retainMiB = Integer.getInteger("lab.retainMiB", 64);
        long before = ProductionLab.heapUsedAfterGc();
        for (int i = 0; i < retainMiB; i++) {
            RETAINED.add(new byte[1024 * 1024]);
        }
        long retained = ProductionLab.heapUsedAfterGc();

        String histogram = ProductionLab.jcmd("gcClassHistogram");
        HistogramLine top = topLines(histogram).getFirst();
        String heapInfo = ProductionLab.jcmd("gcHeapInfo");
        ProductionLab.print("retain " + retainMiB + " x 1 MiB byte[]", "heap used after GC " + ProductionLab.mib(before)
                + " -> " + ProductionLab.mib(retained));
        ProductionLab.print("GC.class_histogram top 3", topLines(histogram).stream().limit(3).map(HistogramLine::toString)
                .reduce((a, b) -> a + " | " + b).orElse(""));
        ProductionLab.print("GC.heap_info", heapInfo.lines().limit(3).map(String::trim).reduce((a, b) -> a + " / " + b).orElse(""));
        ProductionLab.holdForManualInspection(retainMiB + " MiB retained in byte[]");

        assertThat(top.className()).isEqualTo("[B");
        assertThat(top.bytes()).isGreaterThanOrEqualTo(retainMiB * 1024L * 1024L);
        assertThat(retained - before).isGreaterThanOrEqualTo(retainMiB * 1024L * 1024L * 9 / 10);

        RETAINED.clear();
        long released = ProductionLab.heapUsedAfterGc();
        ProductionLab.print("after clearing the list", "heap used after GC " + ProductionLab.mib(released));
        assertThat(retained - released).isGreaterThanOrEqualTo(retainMiB * 1024L * 1024L * 9 / 10);
    }

    @Test
    void anUnboundedMapCacheGrowsWithTrafficABoundedOneDoesNot() {
        int requests = 20_000;
        int valueBytes = 2_048;
        Map<String, byte[]> unbounded = new ConcurrentHashMap<>();
        Map<String, byte[]> bounded = lruCache(1_000);

        long base = ProductionLab.heapUsedAfterGc();
        for (int i = 0; i < requests; i++) {
            // e.g. "cache the rendered response per request/session": the key is unique, nothing is ever reused
            unbounded.computeIfAbsent(UUID.randomUUID().toString(), key -> new byte[valueBytes]);
        }
        long withUnbounded = ProductionLab.heapUsedAfterGc();
        unbounded.clear();
        long afterClear = ProductionLab.heapUsedAfterGc();
        for (int i = 0; i < requests; i++) {
            bounded.computeIfAbsent(UUID.randomUUID().toString(), key -> new byte[valueBytes]);
        }
        long withBounded = ProductionLab.heapUsedAfterGc();

        long unboundedGrowth = withUnbounded - base;
        long boundedGrowth = withBounded - afterClear;
        ProductionLab.print(requests + " unique keys x 2 KiB",
                "ConcurrentHashMap (no eviction): " + unbounded.getClass().getSimpleName() + " retained +"
                        + ProductionLab.mib(unboundedGrowth) + "; LRU(max 1000): size " + bounded.size() + ", retained +"
                        + ProductionLab.mib(boundedGrowth));

        assertThat(bounded).hasSize(1_000);
        assertThat(unboundedGrowth).isGreaterThan((long) requests * valueBytes * 9 / 10);
        assertThat(boundedGrowth).isLessThan(unboundedGrowth / 5);
    }

    /** Minimal bounded cache: LRU eviction by size. A real one adds TTL and metrics (Caffeine: maximumSize + expireAfterWrite). */
    private static <K, V> Map<K, V> lruCache(int maxEntries) {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    private record HistogramLine(long instances, long bytes, String className) {

        @Override
        public String toString() {
            return className + " " + instances + " instances " + ProductionLab.mib(bytes);
        }
    }

    private static List<HistogramLine> topLines(String histogram) {
        List<HistogramLine> lines = new ArrayList<>();
        Matcher matcher = HISTOGRAM_LINE.matcher(histogram);
        while (matcher.find() && lines.size() < 10) {
            lines.add(new HistogramLine(Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)), matcher.group(3)));
        }
        return lines;
    }
}
