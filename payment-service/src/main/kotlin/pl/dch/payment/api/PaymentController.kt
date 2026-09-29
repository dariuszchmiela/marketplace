package pl.dch.payment.api

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.dch.payment.observability.PaymentMetrics
import pl.dch.payment.payments.CreatePaymentCommand
import pl.dch.payment.payments.Payment
import pl.dch.payment.payments.PaymentService
import pl.dch.payment.payments.PaymentStatus
import pl.dch.payment.simulation.ScenarioSimulator

/**
 * No card data on purpose: the marketplace only tells us what to charge for which order.
 * Fields are nullable so that a missing field is reported by Bean Validation, not by Jackson.
 */
data class CreatePaymentRequest(
    @field:NotNull @field:Positive
    val orderId: Long?,
    @field:NotNull @field:DecimalMin("0.01") @field:Digits(integer = 10, fraction = 2)
    val amount: BigDecimal?,
    @field:NotNull @field:Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code, e.g. PLN")
    val currency: String?,
    @field:NotBlank @field:Size(max = 100)
    val idempotencyKey: String?,
) {
    fun toCommand() = CreatePaymentCommand(orderId!!, amount!!, currency!!, idempotencyKey!!)
}

data class PaymentResponse(
    val paymentId: UUID,
    val orderId: Long,
    val status: PaymentStatus,
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKey: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(payment: Payment) = PaymentResponse(
            paymentId = payment.id,
            orderId = payment.orderId,
            status = payment.status,
            amount = payment.amount,
            currency = payment.currency,
            idempotencyKey = payment.idempotencyKey,
            createdAt = payment.createdAt,
        )
    }
}

@RestController
@RequestMapping("/api/payments")
class PaymentController(
    private val paymentService: PaymentService,
    private val simulator: ScenarioSimulator,
    private val metrics: PaymentMetrics,
) {

    /**
     * 201 when the payment was recorded by this request, 200 when the idempotency key was already
     * known and the stored payment is returned. A declined payment is a normal 2xx business result.
     */
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreatePaymentRequest,
        @RequestHeader(name = ScenarioSimulator.HEADER, required = false) scenarioHeader: String?,
    ): ResponseEntity<PaymentResponse> {
        val command = request.toCommand()
        val scenario = simulator.resolve(scenarioHeader)
        metrics.scenario(scenario)

        simulator.beforeProcessing(scenario, command.idempotencyKey)
        val result = paymentService.createPayment(command, simulator.authorizationDecision(scenario))
        simulator.afterProcessing(scenario)

        val body = PaymentResponse.from(result.payment)
        return if (result.created) {
            ResponseEntity.created(URI.create("/api/payments/${body.paymentId}")).body(body)
        } else {
            ResponseEntity.ok(body)
        }
    }

    @GetMapping("/{paymentId}")
    fun findById(@PathVariable paymentId: UUID): PaymentResponse =
        PaymentResponse.from(paymentService.findById(paymentId))

    /** Used by the marketplace to reconcile a payment whose result it did not receive. */
    @GetMapping("/by-idempotency-key/{idempotencyKey}")
    fun findByIdempotencyKey(@PathVariable idempotencyKey: String): PaymentResponse =
        PaymentResponse.from(paymentService.findByIdempotencyKey(idempotencyKey))
}
