package pl.dch.payment.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
 * Security boundary of `/actuator/`: the health probes (`/actuator/health`, `/health/liveness`, `/health/readiness`,
 * status only) are public; everything else — metrics, prometheus, info, the detailed `dependencies` health group —
 * needs `Authorization: Bearer <MANAGEMENT_TOKEN>` (what Prometheus sends). A separate secret from the service
 * token: being able to scrape metrics must not allow creating payments, and vice versa.
 * Dangerous endpoints (env, configprops, heapdump, threaddump, ...) are not exposed over HTTP at all.
 */
class ManagementTokenFilter(token: String, private val metrics: PaymentMetrics, private val jsonMapper: JsonMapper) :
    OncePerRequestFilter() {

    init {
        require(token.isNotBlank()) { "payment.management.token must be set" }
    }

    private val expected = "Bearer $token".toByteArray(StandardCharsets.UTF_8)

    override fun shouldNotFilter(request: HttpServletRequest) =
        !request.requestURI.startsWith("/actuator") || request.requestURI in PUBLIC_PATHS

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && MessageDigest.isEqual(expected, header.toByteArray(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response)
            return
        }
        metrics.managementRejected()
        log.info("security.management_rejected method={} path={} tokenPresent={}", request.method, request.requestURI, header != null)
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        jsonMapper.writeValue(response.outputStream, ApiError(Instant.now(), HttpStatus.UNAUTHORIZED.value(),
            "MANAGEMENT_AUTHENTICATION_REQUIRED", "Management token required", request.requestURI))
    }

    companion object {
        val PUBLIC_PATHS = setOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")
        private val log = LoggerFactory.getLogger(ManagementTokenFilter::class.java)
    }
}

@Configuration
class ManagementSecurityConfiguration {

    @Bean
    fun managementTokenFilter(
        @Value("\${payment.management.token}") token: String,
        metrics: PaymentMetrics,
        jsonMapper: JsonMapper,
    ): FilterRegistrationBean<ManagementTokenFilter> {
        if (token.startsWith("dev-only-")) {
            LoggerFactory.getLogger(ManagementSecurityConfiguration::class.java)
                .warn("security.dev_token_in_use: payment.management.token is the local development default; set MANAGEMENT_TOKEN")
        }
        return FilterRegistrationBean(ManagementTokenFilter(token, metrics, jsonMapper)).apply {
            addUrlPatterns("/actuator", "/actuator/*")
            order = 0
        }
    }
}
