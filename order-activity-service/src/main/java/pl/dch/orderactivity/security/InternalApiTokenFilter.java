package pl.dch.orderactivity.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP API of this service (projection read endpoint, and the simulation endpoints when they are enabled at all)
 * is internal: callers must send {@code Authorization: Bearer <ORDER_ACTIVITY_API_TOKEN>}. The Kafka consumer is not
 * affected. A separate secret from the payment token: one leaked secret must not open both services.
 */
public class InternalApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalApiTokenFilter.class);

    private final byte[] expected;
    private final JsonMapper jsonMapper;

    public InternalApiTokenFilter(String token, JsonMapper jsonMapper) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("order-activity.security.api-token must be set");
        }
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            reject(request, response, "MISSING_API_TOKEN");
        } else if (!MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {   // constant time
            reject(request, response, "INVALID_API_TOKEN");
        } else {
            chain.doFilter(request, response);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String code) throws IOException {
        log.warn("security.internal_api_auth_failed code={} method={} path={}", code, request.getMethod(), request.getRequestURI());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 401);
        body.put("code", code);
        body.put("message", "Internal API: service credentials required");
        body.put("path", request.getRequestURI());
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
