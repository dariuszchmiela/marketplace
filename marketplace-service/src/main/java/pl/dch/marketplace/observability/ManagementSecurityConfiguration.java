package pl.dch.marketplace.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import pl.dch.marketplace.common.ApiError;
import pl.dch.marketplace.common.ErrorCode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security boundary of the management endpoints ({@code /actuator/**}), separate from the browser API.
 * <ul>
 *   <li>{@code /actuator/health}, {@code /health/liveness}, {@code /health/readiness}: public, status only
 *       ({@code UP}/{@code DOWN}), for load balancers and orchestrator probes;</li>
 *   <li>everything else (metrics, prometheus, info, the detailed {@code health/dependencies} group) requires
 *       {@code Authorization: Bearer <MANAGEMENT_TOKEN>} — what Prometheus sends when it scrapes;</li>
 *   <li>stateless: the browser session cookie is <strong>not</strong> read here (a logged-in shopper is not an
 *       operator), no session is created, and therefore no CSRF protection is needed — a bearer token is never sent
 *       automatically by a browser, so a cross-site request cannot carry it;</li>
 *   <li>dangerous endpoints (env, configprops, heapdump, threaddump, ...) are not exposed over HTTP at all
 *       ({@code management.endpoints.web.exposure.include}); dumps are taken locally with {@code jcmd}.</li>
 * </ul>
 * This chain is evaluated before the API chain ({@code @Order(1)}) and only for {@code /actuator/**}.
 */
@Configuration
class ManagementSecurityConfiguration {

    static final String ROLE = "MANAGEMENT";

    private static final Logger log = LoggerFactory.getLogger(ManagementSecurityConfiguration.class);

    @Bean
    @Order(1)
    SecurityFilterChain managementSecurity(HttpSecurity http, @Value("${app.management.token}") String token,
                                           SecurityMetrics metrics, JsonMapper jsonMapper) throws Exception {
        if (token.startsWith("dev-only-")) {
            log.warn("security.dev_token_in_use: app.management.token is the local development default; set MANAGEMENT_TOKEN");
        }
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
            metrics.managementRejected();
            log.info("security.management_rejected method={} path={} tokenPresent={}", request.getMethod(),
                    request.getRequestURI(), request.getHeader(HttpHeaders.AUTHORIZATION) != null);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            jsonMapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), 401,
                    ErrorCode.MANAGEMENT_AUTHENTICATION_REQUIRED, "Management token required", request.getRequestURI(),
                    List.of()));
        };
        http
                .securityMatcher("/actuator/**")
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")
                        .permitAll()
                        .anyRequest().hasRole(ROLE))
                .addFilterBefore(new ManagementTokenFilter(token), AuthorizationFilter.class)
                // Stateless bearer-token API: no cookie is an authentication credential here, so CSRF does not apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler((request, response, ex) -> entryPoint.commence(request, response, null)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Authenticates a request carrying the management token (constant-time comparison, value never logged). Any other
     * request stays anonymous; the authorization rules above then allow only the public health endpoints.
     */
    static final class ManagementTokenFilter extends OncePerRequestFilter {

        private final byte[] expected;

        ManagementTokenFilter(String token) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("app.management.token must be set");
            }
            this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "management-client", null, AuthorityUtils.createAuthorityList("ROLE_" + ROLE)));
                SecurityContextHolder.setContext(context);
            }
            chain.doFilter(request, response);
        }
    }
}
