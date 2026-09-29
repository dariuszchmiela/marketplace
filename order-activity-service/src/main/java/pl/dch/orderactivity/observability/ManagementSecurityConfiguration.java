package pl.dch.orderactivity.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security boundary of {@code /actuator/}: the health probes ({@code /actuator/health}, {@code /health/liveness},
 * {@code /health/readiness}, status only) are public; everything else — metrics, prometheus, info, the detailed
 * {@code dependencies} health group — needs {@code Authorization: Bearer <MANAGEMENT_TOKEN>} (what Prometheus sends).
 * A separate secret from the internal API token. Dangerous endpoints (env, heapdump, threaddump, ...) are not exposed.
 */
@Configuration
class ManagementSecurityConfiguration {

    static final Set<String> PUBLIC_PATHS = Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");

    private static final Logger log = LoggerFactory.getLogger(ManagementSecurityConfiguration.class);

    @Bean
    FilterRegistrationBean<ManagementTokenFilter> managementTokenFilter(
            @Value("${order-activity.management.token}") String token, OrderEventMetrics metrics, JsonMapper jsonMapper) {
        if (token.startsWith("dev-only-")) {
            log.warn("security.dev_token_in_use: order-activity.management.token is the local development default; set MANAGEMENT_TOKEN");
        }
        FilterRegistrationBean<ManagementTokenFilter> registration =
                new FilterRegistrationBean<>(new ManagementTokenFilter(token, metrics, jsonMapper));
        registration.addUrlPatterns("/actuator", "/actuator/*");
        registration.setOrder(0);
        return registration;
    }

    static final class ManagementTokenFilter extends OncePerRequestFilter {

        private final byte[] expected;
        private final OrderEventMetrics metrics;
        private final JsonMapper jsonMapper;

        ManagementTokenFilter(String token, OrderEventMetrics metrics, JsonMapper jsonMapper) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("order-activity.management.token must be set");
            }
            this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
            this.metrics = metrics;
            this.jsonMapper = jsonMapper;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/actuator") || PUBLIC_PATHS.contains(request.getRequestURI());
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
                chain.doFilter(request, response);
                return;
            }
            metrics.managementRejected();
            log.info("security.management_rejected method={} path={} tokenPresent={}", request.getMethod(),
                    request.getRequestURI(), header != null);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", Instant.now().toString());
            body.put("status", 401);
            body.put("code", "MANAGEMENT_AUTHENTICATION_REQUIRED");
            body.put("message", "Management token required");
            body.put("path", request.getRequestURI());
            jsonMapper.writeValue(response.getOutputStream(), body);
        }
    }
}
