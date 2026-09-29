package pl.dch.orderactivity;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("order-activity")
public record OrderActivityProperties(Topics topics, Retry retry, Simulation simulation) {

    public record Topics(String orderEvents, String deadLetter, int partitions) {
    }

    /** Transient failures: {@code maxRetries} redeliveries with exponential backoff, then the dead-letter topic. */
    public record Retry(int maxRetries, Duration initialInterval, double multiplier) {
    }

    public record Simulation(boolean enabled) {
    }
}
