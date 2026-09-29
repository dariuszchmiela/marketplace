package pl.dch.payment.payments

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Repository

/**
 * Phase 2 simplification: payments are kept in memory and are lost on restart. A real provider
 * would store them in a database with a unique constraint on the idempotency key.
 *
 * Thread safety: idempotency relies on [ConcurrentHashMap.computeIfAbsent]. For one key the
 * factory runs at most once; concurrent callers with the same key wait for it and then get the
 * same payment. The factory must therefore stay short (it only builds an object here).
 */
@Repository
class InMemoryPaymentRepository {

    private val byIdempotencyKey = ConcurrentHashMap<String, Payment>()
    private val byId = ConcurrentHashMap<UUID, Payment>()

    fun saveIfAbsent(idempotencyKey: String, create: () -> Payment): PaymentResult {
        // Only the thread that runs the factory sets this, and it reads it after computeIfAbsent returns.
        var created = false
        val payment = byIdempotencyKey.computeIfAbsent(idempotencyKey) {
            create().also { newPayment ->
                byId[newPayment.id] = newPayment
                created = true
            }
        }
        return PaymentResult(payment, created)
    }

    fun findById(id: UUID): Payment? = byId[id]

    fun findByIdempotencyKey(idempotencyKey: String): Payment? = byIdempotencyKey[idempotencyKey]

    fun count(): Int = byId.size
}
