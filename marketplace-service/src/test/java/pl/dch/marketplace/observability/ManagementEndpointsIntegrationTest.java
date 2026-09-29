package pl.dch.marketplace.observability;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import pl.dch.marketplace.IntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security boundary of {@code /actuator/**}: public status-only probes, everything else behind the management
 * token, dangerous endpoints not exposed at all, and the browser session is not a management credential.
 */
class ManagementEndpointsIntegrationTest extends IntegrationTestBase {

    private static final String BEARER = "Bearer " + MANAGEMENT_TOKEN;

    @Test
    void healthProbesArePublicButRevealOnlyTheStatus() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void metricsNeedTheManagementToken() throws Exception {
        for (String path : new String[] {"/actuator/prometheus", "/actuator/metrics", "/actuator/info", "/actuator",
                "/actuator/health/dependencies"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("MANAGEMENT_AUTHENTICATION_REQUIRED"));
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aLoggedInShopperIsNotAnOperator() throws Exception {
        // Valid browser session cookie + CSRF token: still no access, the management chain never reads the session.
        mockMvc.perform(as(user, get("/actuator/prometheus")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(as(user, get("/actuator/metrics")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheusScrapeWithTheTokenNeedsNoSessionAndNoCsrf() throws Exception {
        MvcResult scrape = mockMvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andReturn();

        String body = scrape.getResponse().getContentAsString();
        assertThat(body)
                // HTTP (with histogram buckets), JVM, process, Hikari, business, outbox, sessions, resilience
                .contains("http_server_requests_seconds_bucket")
                .contains("jvm_memory_used_bytes")
                .contains("jvm_threads_live_threads")
                .contains("jvm_classes_loaded_classes")
                .contains("process_cpu_usage")
                .contains("system_cpu_usage")
                .contains("hikaricp_connections_active")
                .contains("hikaricp_connections_pending")
                .contains("hikaricp_connections_max")
                .contains("marketplace_checkout_total")
                .contains("marketplace_checkout_duration_seconds_bucket")
                .contains("outbox_pending_count")
                .contains("outbox_oldest_pending_age_seconds")
                .contains("sessions_active")
                .contains("auth_login_failure_total")
                .contains("security_csrf_rejected_total")
                .contains("resilience4j_circuitbreaker_state")
                .contains("application=\"marketplace-service\"");
        // Stateless: no session, no cookies for the scraper.
        assertThat(scrape.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void dangerousEndpointsAreNotExposedEvenWithTheToken() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/configprops", "/actuator/heapdump",
                "/actuator/threaddump", "/actuator/beans", "/actuator/loggers", "/actuator/shutdown"}) {
            int status = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, BEARER))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(path).isEqualTo(404);
        }
        mockMvc.perform(post("/actuator/shutdown").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
    }

    @Test
    void dependencyHealthShowsEveryDependencyToOperators() throws Exception {
        mockMvc.perform(get("/actuator/health/dependencies").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.paymentService.status").value("UP"))
                .andExpect(jsonPath("$.components.paymentService.details.circuitBreaker").value("CLOSED"))
                .andExpect(jsonPath("$.components.outbox.details.pending").isNumber());
    }

    @Test
    void anOpenCircuitDegradesThePaymentDependencyButTheServiceStaysUpAndReady() throws Exception {
        paymentCircuitBreaker.transitionToOpenState();

        mockMvc.perform(get("/actuator/health/dependencies").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.components.paymentService.status").value("DEGRADED"))
                .andExpect(jsonPath("$.components.paymentService.details.circuitBreaker").value("OPEN"));
        // Readiness and liveness do not depend on payment-service: the catalog, carts and orders still work.
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
