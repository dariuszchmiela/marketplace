package pl.dch.payment.payments

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Final business result of a payment. There are deliberately no intermediate states:
 * a payment is recorded only together with its result.
 */
enum class PaymentStatus { SUCCEEDED, DECLINED }

/** Result of the (simulated) authorization with the card network. */
enum class AuthorizationDecision(val status: PaymentStatus) {
    APPROVED(PaymentStatus.SUCCEEDED),
    DECLINED(PaymentStatus.DECLINED),
}

data class CreatePaymentCommand(
    val orderId: Long,
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKey: String,
)

/** Immutable: once recorded, a payment never changes. */
data class Payment(
    val id: UUID,
    val orderId: Long,
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKey: String,
    val status: PaymentStatus,
    val createdAt: Instant,
) {
    /**
     * Whether a repeated request carries the same business data. Amounts are compared by value,
     * so `10.0` and `10.00` match (`BigDecimal.equals` would also compare the scale).
     */
    fun matches(command: CreatePaymentCommand): Boolean =
        orderId == command.orderId &&
            amount.compareTo(command.amount) == 0 &&
            currency == command.currency
}

/** [created] is false when the idempotency key was already known and the stored payment is returned. */
data class PaymentResult(val payment: Payment, val created: Boolean)

class IdempotencyKeyConflictException(idempotencyKey: String) :
    RuntimeException("Idempotency key '$idempotencyKey' was already used for a payment with different data")

class PaymentNotFoundException(message: String) : RuntimeException(message)
