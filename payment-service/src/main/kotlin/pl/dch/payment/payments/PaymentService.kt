package pl.dch.payment.payments

import java.time.Clock
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.dch.payment.observability.PaymentMetrics

/**
 * Business logic of the payment provider. It knows nothing about failure simulation:
 * the authorization decision is passed in by the caller.
 */
@Service
class PaymentService(
    private val repository: InMemoryPaymentRepository,
    private val clock: Clock,
    private val metrics: PaymentMetrics,
) {

    /**
     * Idempotent by [CreatePaymentCommand.idempotencyKey]: the first call records the payment,
     * every later call with the same key returns that payment (even if [decision] differs).
     * Reusing a key with different business data is rejected.
     */
    fun createPayment(command: CreatePaymentCommand, decision: AuthorizationDecision): PaymentResult {
        val result = repository.saveIfAbsent(command.idempotencyKey) {
            Payment(
                id = UUID.randomUUID(),
                orderId = command.orderId,
                amount = command.amount,
                currency = command.currency,
                idempotencyKey = command.idempotencyKey,
                status = decision.status,
                createdAt = clock.instant(),
            )
        }
        val payment = result.payment
        if (!payment.matches(command)) {
            log.warn(
                "payment.idempotency_conflict idempotencyKey={} storedOrderId={} requestedOrderId={}",
                command.idempotencyKey, payment.orderId, command.orderId,
            )
            throw IdempotencyKeyConflictException(command.idempotencyKey)
        }
        metrics.payment(payment.status, result.created)
        log.info(
            "payment.{} paymentId={} orderId={} status={} amount={} currency={} idempotencyKey={}",
            if (result.created) "created" else "replayed",
            payment.id, payment.orderId, payment.status, payment.amount, payment.currency, payment.idempotencyKey,
        )
        return result
    }

    fun findById(paymentId: UUID): Payment =
        repository.findById(paymentId) ?: throw PaymentNotFoundException("Payment $paymentId not found")

    fun findByIdempotencyKey(idempotencyKey: String): Payment =
        repository.findByIdempotencyKey(idempotencyKey)
            ?: throw PaymentNotFoundException("No payment for idempotency key '$idempotencyKey'")

    private companion object {
        private val log = LoggerFactory.getLogger(PaymentService::class.java)
    }
}
