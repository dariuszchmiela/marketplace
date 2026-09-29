package pl.dch.payment.payments

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PaymentServiceTest {

    private val repository = InMemoryPaymentRepository()
    private val clock = Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC)
    private val service = PaymentService(repository, clock)

    private val command = CreatePaymentCommand(orderId = 7, amount = BigDecimal("129.50"), currency = "PLN", idempotencyKey = "key-1")

    @Test
    fun `approved payment is recorded as SUCCEEDED`() {
        val result = service.createPayment(command, AuthorizationDecision.APPROVED)

        assertThat(result.created).isTrue()
        assertThat(result.payment.status).isEqualTo(PaymentStatus.SUCCEEDED)
        assertThat(result.payment.orderId).isEqualTo(7)
        assertThat(result.payment.createdAt).isEqualTo(clock.instant())
        assertThat(service.findByIdempotencyKey("key-1")).isEqualTo(result.payment)
        assertThat(service.findById(result.payment.id)).isEqualTo(result.payment)
    }

    @Test
    fun `declined payment is a recorded business result`() {
        val result = service.createPayment(command, AuthorizationDecision.DECLINED)

        assertThat(result.payment.status).isEqualTo(PaymentStatus.DECLINED)
        assertThat(repository.count()).isEqualTo(1)
    }

    @Test
    fun `same idempotency key returns the stored payment instead of creating another one`() {
        val first = service.createPayment(command, AuthorizationDecision.APPROVED)
        // The decision of a replay is ignored: the first result is final.
        val second = service.createPayment(command.copy(amount = BigDecimal("129.5")), AuthorizationDecision.DECLINED)

        assertThat(second.created).isFalse()
        assertThat(second.payment).isEqualTo(first.payment)
        assertThat(repository.count()).isEqualTo(1)
    }

    @Test
    fun `reusing a key with different business data is rejected`() {
        service.createPayment(command, AuthorizationDecision.APPROVED)

        listOf(
            command.copy(orderId = 8),
            command.copy(amount = BigDecimal("129.51")),
            command.copy(currency = "EUR"),
        ).forEach { conflicting ->
            assertThatThrownBy { service.createPayment(conflicting, AuthorizationDecision.APPROVED) }
                .isInstanceOf(IdempotencyKeyConflictException::class.java)
        }
        assertThat(repository.count()).isEqualTo(1)
    }

    @Test
    fun `unknown payment is reported as not found`() {
        assertThatThrownBy { service.findByIdempotencyKey("missing") }
            .isInstanceOf(PaymentNotFoundException::class.java)
    }

    @Test
    fun `concurrent requests with the same key create exactly one payment`() {
        val threads = 32
        val executor = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (1..threads).map {
                executor.submit<PaymentResult> {
                    start.await()
                    service.createPayment(command, AuthorizationDecision.APPROVED)
                }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(results.map { it.payment.id }.toSet()).hasSize(1)
            assertThat(results.count { it.created }).isEqualTo(1)
            assertThat(repository.count()).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }
}
