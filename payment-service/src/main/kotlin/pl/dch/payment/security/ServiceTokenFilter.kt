package pl.dch.payment.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import pl.dch.payment.api.ApiError
import tools.jackson.databind.json.JsonMapper

/**
 * `payment.security.*`. The token is a shared secret between marketplace-service and payment-service
 * (`PAYMENT_SERVICE_TOKEN`). The local default in application.yaml is for development only.
 */
@ConfigurationProperties("payment.security")
data class ServiceSecurityProperties(val serviceToken: String) {

    init {
        require(serviceToken.isNotBlank()) { "payment.security.service-token must be set" }
    }

    /** Never print the secret (configuration dumps, failure analysis). */
    override fun toString() = "ServiceSecurityProperties(serviceToken=***)"
}

/**
 * Service-to-service authentication: every call under `/api/` must carry `Authorization: Bearer <service token>`.
 * Only the marketplace knows the token, so random callers on the network cannot create or read payments.
 * A plain servlet filter is enough for one shared secret; real systems would rather use mTLS, workload identity
 * or OAuth2 client credentials (see docs/architecture.md).
 */
class ServiceTokenFilter(properties: ServiceSecurityProperties, private val jsonMapper: JsonMapper) : OncePerRequestFilter() {

    private val expected = "Bearer ${properties.serviceToken}".toByteArray(StandardCharsets.UTF_8)

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        when {
            header == null -> reject(request, response, "MISSING_SERVICE_TOKEN", "Missing service credentials")
            // Constant-time comparison: the time taken must not reveal how many leading characters were right.
            !MessageDigest.isEqual(expected, header.toByteArray(StandardCharsets.UTF_8)) ->
                reject(request, response, "INVALID_SERVICE_TOKEN", "Invalid service credentials")
            else -> chain.doFilter(request, response)
        }
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse, code: String, message: String) {
        // Never log the header value itself.
        log.warn("security.service_auth_failed code={} method={} path={} remote={}",
            code, request.method, request.requestURI, request.remoteAddr)
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        jsonMapper.writeValue(response.outputStream,
            ApiError(Instant.now(), HttpStatus.UNAUTHORIZED.value(), code, message, request.requestURI))
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ServiceTokenFilter::class.java)
    }
}

@Configuration
class ServiceSecurityConfiguration {

    @Bean
    fun serviceTokenFilter(properties: ServiceSecurityProperties, jsonMapper: JsonMapper): FilterRegistrationBean<ServiceTokenFilter> {
        if (properties.serviceToken.startsWith("dev-only-")) {
            LoggerFactory.getLogger(ServiceSecurityConfiguration::class.java)
                .warn("security.dev_token_in_use: payment.security.service-token is the local development default; set PAYMENT_SERVICE_TOKEN")
        }
        return FilterRegistrationBean(ServiceTokenFilter(properties, jsonMapper)).apply {
            addUrlPatterns("/api/*")
            order = 0
        }
    }
}
