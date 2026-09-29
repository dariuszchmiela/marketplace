package pl.dch.payment.observability

import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

private const val SERVICE_TOKEN = "test-service-token"
private const val MANAGEMENT_TOKEN = "test-management-token"

/** Management endpoint boundary, business metrics and trace continuation (the marketplace's traceparent). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["payment.security.service-token=$SERVICE_TOKEN", "payment.management.token=$MANAGEMENT_TOKEN"],
)
@AutoConfigureMetrics
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension::class)
class ObservabilityIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private data class Reply(val status: Int, val body: String)

    @Test
    fun `health probes are public and show only the status`() {
        for (path in listOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            val reply = get(path, authorization = null)
            assertThat(reply.status).`as`(path).isEqualTo(200)
            assertThat(reply.body).contains("\"status\":\"UP\"").doesNotContain("components")
        }
    }

    @Test
    fun `metrics need the management token and the service token is not enough`() {
        for (path in listOf("/actuator/prometheus", "/actuator/metrics", "/actuator/health/dependencies", "/actuator")) {
            assertThat(get(path, authorization = null).status).`as`(path).isEqualTo(401)
            assertThat(get(path, authorization = "Bearer $SERVICE_TOKEN").status).`as`(path).isEqualTo(401)
        }
        val rejected = get("/actuator/prometheus", authorization = null)
        assertThat(rejected.body).contains("MANAGEMENT_AUTHENTICATION_REQUIRED")
        assertThat(get("/actuator/health/dependencies", authorization = "Bearer $MANAGEMENT_TOKEN").body)
            .contains("diskSpace")
    }

    @Test
    fun `dangerous endpoints are not exposed even with the token`() {
        for (path in listOf("/actuator/env", "/actuator/configprops", "/actuator/heapdump", "/actuator/threaddump", "/actuator/beans")) {
            assertThat(get(path, authorization = "Bearer $MANAGEMENT_TOKEN").status).`as`(path).isEqualTo(404)
        }
    }

    @Test
    fun `payments, scenarios and rejected callers are counted with bounded tags`() {
        createPayment(UUID.randomUUID().toString(), scenario = "DECLINED", authorization = "Bearer $SERVICE_TOKEN")
        createPayment(UUID.randomUUID().toString(), scenario = null, authorization = "Bearer wrong")

        val scrape = get("/actuator/prometheus", authorization = "Bearer $MANAGEMENT_TOKEN")

        assertThat(scrape.status).isEqualTo(200)
        assertThat(scrape.body)
            .contains("payment_service_payments_total{application=\"payment-service\",result=\"created\",status=\"DECLINED\"}")
            .contains("payment_simulation_scenario_total{application=\"payment-service\",scenario=\"DECLINED\"}")
            .contains("security_service_auth_rejected_total{application=\"payment-service\",reason=\"INVALID_SERVICE_TOKEN\"}")
            .contains("http_server_requests_seconds_bucket")
            .contains("uri=\"/api/payments\"")
            .contains("jvm_memory_used_bytes")
            .doesNotContain(SERVICE_TOKEN)
            .doesNotContain(MANAGEMENT_TOKEN)
        assertThat(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(labelValues(scrape.body)))
            .isFalse()
    }

    @Test
    fun `an incoming W3C traceparent is continued and appears in the logs`(output: CapturedOutput) {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val key = UUID.randomUUID().toString()

        createPayment(key, scenario = null, authorization = "Bearer $SERVICE_TOKEN",
            traceparent = "00-$traceId-00f067aa0ba902b7-01")

        // The marketplace's trace id, in the MDC of the log line written while handling the request.
        assertThat(output.lines().filter { it.contains("payment.created") && it.contains(key) })
            .singleElement().asString().contains("traceId=$traceId")
    }

    private fun labelValues(scrape: String): String =
        Regex("=\"([^\"]*)\"").findAll(scrape).joinToString("\n") { it.groupValues[1] }

    private fun get(path: String, authorization: String?): Reply =
        RestClient.create("http://localhost:$port").get().uri(path)
            .headers { headers -> authorization?.let { headers.set("Authorization", it) } }
            .exchange { _, response -> Reply(response.statusCode.value(), response.bodyTo(String::class.java) ?: "") }

    private fun createPayment(key: String, scenario: String?, authorization: String, traceparent: String? = null): Reply =
        RestClient.create("http://localhost:$port").post().uri("/api/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers ->
                headers.set("Authorization", authorization)
                scenario?.let { headers.set("X-Payment-Scenario", it) }
                traceparent?.let { headers.set("traceparent", it) }
            }
            .body("""{"orderId": 7, "amount": 10.00, "currency": "PLN", "idempotencyKey": "$key"}""")
            .exchange { _, response -> Reply(response.statusCode.value(), response.bodyTo(String::class.java) ?: "") }
}
