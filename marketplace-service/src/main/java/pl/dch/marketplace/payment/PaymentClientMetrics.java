package pl.dch.marketplace.payment;

import java.net.http.HttpTimeoutException;
import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * One measurement per <em>logical</em> call of {@link PaymentClient} (all retry attempts included), i.e. what the
 * checkout waited for. Single HTTP attempts are measured separately by Spring's {@code http.client.requests}
 * observation (uri template, status), so "3 attempts, 1 logical call" is visible by comparing both.
 * <ul>
 *   <li>{@code payment.client.calls{operation, result}} — counter;</li>
 *   <li>{@code payment.client.duration{operation, result}} — timer with a percentile histogram (p50/p95/p99).</li>
 * </ul>
 * Tags are bounded: {@code operation} = pay | lookup, {@code result} = {@link Result}. Never an order, payment or
 * idempotency key.
 */
public class PaymentClientMetrics {

    public enum Result {
        SUCCESS, DECLINED, NOT_FOUND, TIMEOUT, SERVER_ERROR, CONNECTION_ERROR, CIRCUIT_OPEN, OTHER;

        String tag() {
            return name().toLowerCase();
        }

        static Result of(Throwable failure) {
            if (failure instanceof CallNotPermittedException) {
                return CIRCUIT_OPEN;
            }
            if (failure instanceof HttpServerErrorException) {
                return SERVER_ERROR;
            }
            if (failure instanceof HttpClientErrorException.NotFound) {
                return NOT_FOUND;
            }
            if (PaymentClient.isConnectFailure(failure)) {
                return CONNECTION_ERROR;
            }
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException) {
                    return TIMEOUT;
                }
            }
            return OTHER;
        }
    }

    enum Operation { PAY, LOOKUP }

    static final String CALLS = "payment.client.calls";
    static final String DURATION = "payment.client.duration";

    private final MeterRegistry registry;

    public PaymentClientMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Counters exist from the start (value 0): a series that first appears with a non-zero value is invisible to
        // Prometheus rate()/increase() — e.g. the first burst of circuit_open would be missed by an alert. Only the
        // counters: pre-registering every timer would create all histogram buckets for results that may never happen.
        for (Operation operation : Operation.values()) {
            for (Result result : Result.values()) {
                counter(operation, result);
            }
        }
    }

    void record(Operation operation, Result result, Duration elapsed) {
        counter(operation, result).increment();
        Timer.builder(DURATION)
                .description("Duration of a logical payment-service call, retries and backoff included")
                .tags("operation", operation.name().toLowerCase(), "result", result.tag())
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry)
                .record(elapsed);
    }

    private Counter counter(Operation operation, Result result) {
        return Counter.builder(CALLS)
                .description("Logical payment-service calls (retries included) by result")
                .tags("operation", operation.name().toLowerCase(), "result", result.tag())
                .register(registry);
    }
}
