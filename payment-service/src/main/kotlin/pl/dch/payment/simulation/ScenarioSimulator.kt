package pl.dch.payment.simulation

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import pl.dch.payment.payments.AuthorizationDecision

/**
 * Deterministic failure scenarios, selected per request with the [ScenarioSimulator.HEADER] header.
 * Nothing is random, so every scenario can be reproduced in tests and smoke tests.
 */
enum class PaymentScenario {
    /** Normal processing, payment succeeds. Also used when no header is sent. */
    SUCCESS,

    /** Normal processing, the card network declines: a valid business result, not an error. */
    DECLINED,

    /** Waits `slow-delay` BEFORE processing, then records a successful payment. */
    SLOW,

    /** Responds 503 without recording anything, on every attempt. */
    SERVER_ERROR,

    /** Responds 503 without recording anything on the first request for an idempotency key, then processes normally. */
    SERVER_ERROR_ONCE,

    /** Records a successful payment, then waits `slow-delay` before responding (lost/late response). */
    SUCCESS_BUT_SLOW_RESPONSE,
}

@ConfigurationProperties("payment.simulation")
data class SimulationProperties(
    /** When false the scenario header is ignored and every request is processed normally. */
    val enabled: Boolean,
    val slowDelay: Duration,
)

class InvalidScenarioException(value: String) :
    RuntimeException("Unknown payment scenario '$value'. Allowed: ${PaymentScenario.entries.joinToString()}")

/** Mapped to 503. The contract is that nothing was recorded, so the caller may retry with the same key. */
class SimulatedServerErrorException(scenario: PaymentScenario) :
    RuntimeException("Simulated payment-service failure ($scenario); no payment was recorded")

/**
 * Keeps all failure simulation out of [pl.dch.payment.payments.PaymentService]. The controller calls
 * the hooks around normal processing: [beforeProcessing], [authorizationDecision], [afterProcessing].
 */
@Component
class ScenarioSimulator(private val properties: SimulationProperties) {

    private val keysThatFailedOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun resolve(headerValue: String?): PaymentScenario {
        if (headerValue.isNullOrBlank()) {
            return PaymentScenario.SUCCESS
        }
        if (!properties.enabled) {
            log.warn("simulation.disabled ignoring {}={}", HEADER, headerValue)
            return PaymentScenario.SUCCESS
        }
        return PaymentScenario.entries.firstOrNull { it.name == headerValue.trim().uppercase() }
            ?: throw InvalidScenarioException(headerValue)
    }

    fun beforeProcessing(scenario: PaymentScenario, idempotencyKey: String) {
        when (scenario) {
            PaymentScenario.SLOW -> delay(scenario, "before processing")
            PaymentScenario.SERVER_ERROR -> fail(scenario, idempotencyKey)
            PaymentScenario.SERVER_ERROR_ONCE -> if (keysThatFailedOnce.add(idempotencyKey)) fail(scenario, idempotencyKey)
            else -> Unit
        }
    }

    fun authorizationDecision(scenario: PaymentScenario): AuthorizationDecision =
        if (scenario == PaymentScenario.DECLINED) AuthorizationDecision.DECLINED else AuthorizationDecision.APPROVED

    fun afterProcessing(scenario: PaymentScenario) {
        if (scenario == PaymentScenario.SUCCESS_BUT_SLOW_RESPONSE) {
            delay(scenario, "after the payment was recorded")
        }
    }

    private fun fail(scenario: PaymentScenario, idempotencyKey: String): Nothing {
        log.warn("simulation.server_error scenario={} idempotencyKey={}", scenario, idempotencyKey)
        throw SimulatedServerErrorException(scenario)
    }

    private fun delay(scenario: PaymentScenario, moment: String) {
        log.info("simulation.delay scenario={} delay={} moment=\"{}\"", scenario, properties.slowDelay, moment)
        Thread.sleep(properties.slowDelay)
    }

    companion object {
        const val HEADER = "X-Payment-Scenario"
        private val log = LoggerFactory.getLogger(ScenarioSimulator::class.java)
    }
}
