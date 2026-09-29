package pl.dch.marketplace.outbox;

import java.time.Duration;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code outbox.*} in application.yaml. No defaults in code, like the payment client settings.
 */
@Validated
@ConfigurationProperties("outbox")
public record OutboxProperties(
        /* aggregate type -> Kafka topic, e.g. Order -> marketplace.order-events */
        @NotEmpty Map<String, String> topics,
        /* partitions of the topics created by this service */
        @Min(1) int topicPartitions,
        @NotNull Publisher publisher
) {

    public record Publisher(
            /* false: no scheduled polling (tests trigger publishPendingBatch explicitly) */
            boolean enabled,
            @NotNull Duration pollInterval,
            /* rows claimed per poll: bounds the work (and the time the claiming transaction stays open) */
            @Min(1) int batchSize,
            /* max time to wait for the broker acknowledgement of one record */
            @NotNull Duration sendTimeout,
            /* backoff after a failed publication of a row: initial * 2^(attempts-1), capped */
            @NotNull Duration initialRetryBackoff,
            @NotNull Duration maxRetryBackoff
    ) {
    }
}
