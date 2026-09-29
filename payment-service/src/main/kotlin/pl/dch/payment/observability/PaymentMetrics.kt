package pl.dch.payment.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import pl.dch.payment.payments.PaymentStatus
import pl.dch.payment.simulation.PaymentScenario

/**
 * Business and security counters of payment-service. Every tag has a small, fixed set of values (enum names);
 * payment ids, order ids and idempotency keys never become tags. Latency comes from `http.server.requests`.
 *
 * - `payment.service.payments{status, result}` — status SUCCEEDED|DECLINED, result created|replayed (idempotent replay);
 * - `payment.simulation.scenario{scenario}` — which failure scenario was requested (SLOW, SERVER_ERROR, ...);
 * - `security.service_auth.rejected{reason}` — callers without a valid service token;
 * - `security.management.rejected` — management endpoint requests without the management token.
 */
@Component
class PaymentMetrics(private val registry: MeterRegistry) {

    init {
        // Security counters exist from the start (value 0): a series that first appears with a non-zero value is
        // invisible to rate()/increase(), so the first rejected caller would not show up in an alert.
        listOf("MISSING_SERVICE_TOKEN", "INVALID_SERVICE_TOKEN").forEach { serviceAuthRejectedCounter(it) }
        managementRejectedCounter()
    }

    fun payment(status: PaymentStatus, created: Boolean) {
        Counter.builder("payment.service.payments")
            .description("Payments processed by this service")
            .tags("status", status.name, "result", if (created) "created" else "replayed")
            .register(registry)
            .increment()
    }

    fun scenario(scenario: PaymentScenario) {
        Counter.builder("payment.simulation.scenario")
            .description("Requested simulation scenarios")
            .tag("scenario", scenario.name)
            .register(registry)
            .increment()
    }

    fun serviceAuthRejected(code: String) = serviceAuthRejectedCounter(code).increment()

    fun managementRejected() = managementRejectedCounter().increment()

    private fun serviceAuthRejectedCounter(code: String): Counter =
        Counter.builder("security.service_auth.rejected")
            .description("Requests rejected for a missing/invalid service token")
            .tag("reason", code)
            .register(registry)

    private fun managementRejectedCounter(): Counter =
        Counter.builder("security.management.rejected")
            .description("Management endpoint requests without a valid management token")
            .register(registry)
}
