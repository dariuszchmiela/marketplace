package pl.dch.marketplace.lab;

import java.time.Duration;
import java.util.function.Supplier;

/** Lab only: measures wall-clock time and prints observed values as {@code [LAB] …} lines. */
final class LabReport {

    private LabReport() {
    }

    record Timed<T>(T result, Duration elapsed) {

        long millis() {
            return elapsed.toMillis();
        }
    }

    static <T> Timed<T> time(Supplier<T> work) {
        long start = System.nanoTime();
        T result = work.get();
        return new Timed<>(result, Duration.ofNanos(System.nanoTime() - start));
    }

    static void print(String experiment, String observation) {
        System.out.printf("[LAB] %-48s %s%n", experiment, observation);
    }
}
