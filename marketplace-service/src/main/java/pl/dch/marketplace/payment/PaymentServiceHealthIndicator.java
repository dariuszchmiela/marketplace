package pl.dch.marketplace.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * payment-service as seen by the marketplace — <strong>passively</strong>: derived from the circuit breaker, which
 * already records the outcome of every real payment call. The health endpoint never calls payment-service itself.
 * <p>
 * Why passive: probes run on every health request of every instance (load balancer, orchestrator, monitoring). An
 * active probe would add load exactly when payment-service is struggling, and a slow probe would make our own health
 * endpoint slow — a failure spreading upstream instead of being contained.
 * <p>
 * An open circuit reports DEGRADED, not DOWN: it is shown in the token-protected {@code dependencies} health group but
 * affects neither readiness nor the public health status (DEGRADED ranks below UP there, see application.yaml). Without
 * payment-service the marketplace still serves the catalog, carts and orders, and checkout degrades to
 * PAYMENT_FAILED (nothing charged) or PAYMENT_UNKNOWN (reconciled later). Taking every instance out of the load
 * balancer because of one downstream would turn a partial outage into a total one.
 */
@Component("paymentService")
class PaymentServiceHealthIndicator implements HealthIndicator {

    /** Dependency impaired, service still usable. Ranks below UP for the public endpoint (see application.yaml). */
    static final Status DEGRADED = new Status("DEGRADED", "payment-service calls are failing; checkout degrades safely");

    private final CircuitBreaker circuitBreaker;

    PaymentServiceHealthIndicator(CircuitBreaker paymentCircuitBreaker) {
        this.circuitBreaker = paymentCircuitBreaker;
    }

    @Override
    public Health health() {
        CircuitBreaker.State state = circuitBreaker.getState();
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        Health.Builder builder = state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN
                ? Health.status(DEGRADED) : Health.up();
        return builder
                .withDetail("source", "circuit breaker (passive, no probe)")
                .withDetail("circuitBreaker", state.name())
                .withDetail("failureRatePercent", metrics.getFailureRate())
                .withDetail("bufferedCalls", metrics.getNumberOfBufferedCalls())
                .withDetail("failedCalls", metrics.getNumberOfFailedCalls())
                .withDetail("notPermittedCalls", metrics.getNumberOfNotPermittedCalls())
                .build();
    }
}
