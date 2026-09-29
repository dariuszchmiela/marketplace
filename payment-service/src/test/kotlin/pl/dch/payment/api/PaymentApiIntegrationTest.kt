package pl.dch.payment.api

import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import pl.dch.payment.payments.InMemoryPaymentRepository
import pl.dch.payment.simulation.ScenarioSimulator
import tools.jackson.databind.json.JsonMapper

/**
 * Real HTTP against the running application (random port), because timing and concurrency
 * are what matter for this service.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["payment.simulation.slow-delay=400ms"],
)
class PaymentApiIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Autowired
    private lateinit var repository: InMemoryPaymentRepository

    private lateinit var client: RestClient

    private data class Reply(val status: HttpStatus, val body: String)

    @BeforeEach
    fun setUp() {
        client = RestClient.create("http://localhost:$port")
    }

    @Test
    fun `successful payment is created and can be read by id and by idempotency key`() {
        val key = newKey()

        val reply = post(paymentJson(orderId = 1, amount = "99.90", key = key))

        assertThat(reply.status).isEqualTo(HttpStatus.CREATED)
        val payment = payment(reply)
        assertThat(payment.status.name).isEqualTo("SUCCEEDED")
        assertThat(payment.amount).isEqualByComparingTo("99.90")
        assertThat(payment.currency).isEqualTo("PLN")
        assertThat(payment(get("/api/payments/${payment.paymentId}"))).isEqualTo(payment)
        assertThat(payment(get("/api/payments/by-idempotency-key/$key"))).isEqualTo(payment)
    }

    @Test
    fun `declined payment is a 2xx business result`() {
        val reply = post(paymentJson(key = newKey()), scenario = "DECLINED")

        assertThat(reply.status).isEqualTo(HttpStatus.CREATED)
        assertThat(payment(reply).status.name).isEqualTo("DECLINED")
    }

    @Test
    fun `repeated request with the same key returns the same payment with 200`() {
        val json = paymentJson(key = newKey())
        val first = payment(post(json))

        val replay = post(json)

        assertThat(replay.status).isEqualTo(HttpStatus.OK)
        assertThat(payment(replay)).isEqualTo(first)
    }

    @Test
    fun `reusing a key for another order is rejected with 409`() {
        val key = newKey()
        post(paymentJson(orderId = 1, key = key))

        val reply = post(paymentJson(orderId = 2, key = key))

        assertThat(reply.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(reply.body).contains("IDEMPOTENCY_KEY_CONFLICT")
    }

    @Test
    fun `invalid request is rejected with field errors`() {
        val reply = post("""{"orderId": 1, "amount": 0, "currency": "pln"}""")

        assertThat(reply.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(reply.body).contains("VALIDATION_FAILED", "amount", "currency", "idempotencyKey")
    }

    @Test
    fun `unknown scenario is rejected`() {
        val reply = post(paymentJson(key = newKey()), scenario = "SOMETIMES")

        assertThat(reply.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(reply.body).contains("INVALID_SCENARIO")
    }

    @Test
    fun `SERVER_ERROR responds 503 and records nothing`() {
        val key = newKey()

        val reply = post(paymentJson(key = key), scenario = "SERVER_ERROR")

        assertThat(reply.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(reply.body).contains("PAYMENT_SERVICE_UNAVAILABLE")
        assertThat(get("/api/payments/by-idempotency-key/$key").status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `SERVER_ERROR_ONCE fails the first attempt and succeeds when retried with the same key`() {
        val json = paymentJson(key = newKey())

        assertThat(post(json, scenario = "SERVER_ERROR_ONCE").status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        val retry = post(json, scenario = "SERVER_ERROR_ONCE")

        assertThat(retry.status).isEqualTo(HttpStatus.CREATED)
        assertThat(payment(retry).status.name).isEqualTo("SUCCEEDED")
    }

    @Test
    fun `SLOW delays before the payment is recorded`() {
        val key = newKey()
        val response = CompletableFuture.supplyAsync { post(paymentJson(key = key), scenario = "SLOW") }

        Thread.sleep(150)
        assertThat(get("/api/payments/by-idempotency-key/$key").status).isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(response.get(5, TimeUnit.SECONDS).status).isEqualTo(HttpStatus.CREATED)
        assertThat(get("/api/payments/by-idempotency-key/$key").status).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `SUCCESS_BUT_SLOW_RESPONSE records the payment before the delayed response`() {
        val key = newKey()
        val response = CompletableFuture.supplyAsync { post(paymentJson(key = key), scenario = "SUCCESS_BUT_SLOW_RESPONSE") }

        Thread.sleep(150)
        // The caller is still waiting, but the payment already exists: this is what reconciliation finds.
        assertThat(response).isNotDone()
        val stored = get("/api/payments/by-idempotency-key/$key")
        assertThat(stored.status).isEqualTo(HttpStatus.OK)
        assertThat(payment(stored).status.name).isEqualTo("SUCCEEDED")

        assertThat(payment(response.get(5, TimeUnit.SECONDS))).isEqualTo(payment(stored))
    }

    @Test
    fun `concurrent HTTP requests with the same key create exactly one payment`() {
        val json = paymentJson(key = newKey())
        val before = repository.count()
        val threads = 16
        val executor = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (1..threads).map {
                executor.submit<Reply> {
                    start.await()
                    post(json)
                }
            }
            start.countDown()
            val replies = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(replies.map { payment(it).paymentId }.toSet()).hasSize(1)
            assertThat(replies.count { it.status == HttpStatus.CREATED }).isEqualTo(1)
            assertThat(replies.count { it.status == HttpStatus.OK }).isEqualTo(threads - 1)
            assertThat(repository.count()).isEqualTo(before + 1)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun post(json: String, scenario: String? = null): Reply =
        client.post()
            .uri("/api/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers -> scenario?.let { headers.set(ScenarioSimulator.HEADER, it) } }
            .body(json)
            .exchange { _, response -> Reply(HttpStatus.valueOf(response.statusCode.value()), response.bodyTo(String::class.java) ?: "") }

    private fun get(path: String): Reply =
        client.get()
            .uri(path)
            .exchange { _, response -> Reply(HttpStatus.valueOf(response.statusCode.value()), response.bodyTo(String::class.java) ?: "") }

    private fun payment(reply: Reply): PaymentResponse = jsonMapper.readValue(reply.body, PaymentResponse::class.java)

    private fun newKey() = UUID.randomUUID().toString()

    private fun paymentJson(orderId: Long = 1, amount: String = "10.00", key: String) =
        """{"orderId": $orderId, "amount": ${BigDecimal(amount)}, "currency": "PLN", "idempotencyKey": "$key"}"""
}
