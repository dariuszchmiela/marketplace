package pl.dch.payment.security

import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

private const val TOKEN = "test-service-token"

/** payment-service only answers callers that present the shared service token. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["payment.security.service-token=$TOKEN"],
)
@ExtendWith(OutputCaptureExtension::class)
class ServiceTokenIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private data class Reply(val status: Int, val body: String, val wwwAuthenticate: String?)

    @Test
    fun `request without a token is rejected with a JSON 401`() {
        val reply = post(authorization = null)

        assertThat(reply.status).isEqualTo(401)
        assertThat(reply.body).contains("\"code\":\"MISSING_SERVICE_TOKEN\"")
        assertThat(reply.wwwAuthenticate).isEqualTo("Bearer")
    }

    @Test
    fun `request with a wrong token is rejected and the token is not logged`(output: CapturedOutput) {
        val reply = post(authorization = "Bearer not-the-token")

        assertThat(reply.status).isEqualTo(401)
        assertThat(reply.body).contains("\"code\":\"INVALID_SERVICE_TOKEN\"")
        assertThat(output).contains("security.service_auth_failed code=INVALID_SERVICE_TOKEN")
            .doesNotContain("not-the-token").doesNotContain(TOKEN)
    }

    @Test
    fun `other authorization schemes are rejected`() {
        assertThat(post(authorization = "Basic dXNlcjpwYXNz").status).isEqualTo(401)
        assertThat(post(authorization = TOKEN).status).isEqualTo(401)   // the raw token without "Bearer "
    }

    @Test
    fun `request with the right token works for payments and reconciliation lookups`() {
        val key = UUID.randomUUID().toString()

        assertThat(post(authorization = "Bearer $TOKEN", key = key).status).isEqualTo(201)
        val lookup = RestClient.create("http://localhost:$port").get()
            .uri("/api/payments/by-idempotency-key/{key}", key)
            .header("Authorization", "Bearer $TOKEN")
            .exchange { _, response -> response.statusCode.value() }
        assertThat(lookup).isEqualTo(200)
    }

    @Test
    fun `lookups are protected too`() {
        val status = RestClient.create("http://localhost:$port").get()
            .uri("/api/payments/by-idempotency-key/{key}", "anything")
            .exchange { _, response -> response.statusCode.value() }

        assertThat(status).isEqualTo(401)
    }

    private fun post(authorization: String?, key: String = UUID.randomUUID().toString()): Reply =
        RestClient.create("http://localhost:$port").post()
            .uri("/api/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers -> authorization?.let { headers.set("Authorization", it) } }
            .body("""{"orderId": 1, "amount": 10.00, "currency": "PLN", "idempotencyKey": "$key"}""")
            .exchange { _, response ->
                Reply(response.statusCode.value(), response.bodyTo(String::class.java) ?: "",
                    response.headers.getFirst("WWW-Authenticate"))
            }

}
