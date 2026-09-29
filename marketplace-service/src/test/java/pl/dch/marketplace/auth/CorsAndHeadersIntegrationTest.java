package pl.dch.marketplace.auth;

import org.junit.jupiter.api.Test;
import pl.dch.marketplace.IntegrationTestBase;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS relaxes the browser's same-origin policy for the configured frontend origin only (default
 * {@code http://localhost:5173}), with credentials (cookies) — so the allowed origin is echoed, never {@code *}.
 */
class CorsAndHeadersIntegrationTest extends IntegrationTestBase {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Test
    void preflightFromTheConfiguredOriginIsAllowedWithCredentials() throws Exception {
        mockMvc.perform(options("/api/cart/items")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("x-xsrf-token")));
    }

    @Test
    void anUnknownOriginGetsNoCorsAccess() throws Exception {
        mockMvc.perform(options("/api/cart/items")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mockMvc.perform(get("/api/products").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void simpleRequestFromTheAllowedOriginGetsTheExactOriginBackNeverAWildcard() throws Exception {
        mockMvc.perform(get("/api/products").header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void securityHeadersAreSet() throws Exception {
        mockMvc.perform(getWithSession("/api/cart"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                // HSTS only over HTTPS; on plain HTTP a browser would ignore it anyway.
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        mockMvc.perform(get("/api/products").secure(true))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=")));
    }
}
