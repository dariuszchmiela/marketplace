package pl.dch.marketplace.payment;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code payment.client.*} in application.yaml. There are no defaults in code: every timeout and
 * resilience setting must be configured explicitly, otherwise the application does not start.
 */
@Validated
@ConfigurationProperties("payment.client")
public record PaymentClientProperties(
        @NotNull URI baseUrl,
        /* Max time to establish the TCP connection. */
        @NotNull Duration connectTimeout,
        /* Max time from sending the request until the response headers arrive. */
        @NotNull Duration readTimeout,
        /* Dev/test only: pass the client's X-Payment-Scenario header on to payment-service. */
        boolean forwardScenarioHeader,
        @NotNull @Valid Retry retry,
        @NotNull @Valid CircuitBreaker circuitBreaker
) {

    public record Retry(
            /* Total attempts including the first one. */
            @Min(1) int maxAttempts,
            @NotNull Duration initialBackoff,
            @DecimalMin("1.0") double backoffMultiplier,
            /* Jitter: each wait is randomized by +/- this fraction. */
            @DecimalMin("0.0") @DecimalMax("0.99") double randomizationFactor
    ) {
    }

    public record CircuitBreaker(
            /* Failure rate in percent that opens the circuit. */
            @DecimalMin("1") @DecimalMax("100") float failureRateThreshold,
            /* Number of most recent calls the failure rate is computed over. */
            @Min(1) int slidingWindowSize,
            /* The circuit cannot open before this many calls were recorded. */
            @Min(1) int minimumNumberOfCalls,
            @NotNull Duration waitDurationInOpenState,
            @Min(1) int permittedCallsInHalfOpenState
    ) {
    }
}
