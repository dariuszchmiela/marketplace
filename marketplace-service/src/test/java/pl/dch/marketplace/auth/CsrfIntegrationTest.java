package pl.dch.marketplace.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.TestBrowser;
import pl.dch.marketplace.product.Product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The browser attaches the session cookie to any request to this site, also one triggered by a malicious page.
 * The CSRF token (readable only by same-site JavaScript, sent back as a header) proves the request came from our app.
 */
class CsrfIntegrationTest extends IntegrationTestBase {

    @Test
    void stateChangingRequestWithAValidTokenWorks() throws Exception {
        Product lamp = createProduct("Csrf Lamp", "10.00", 5);

        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(lamp.getId(), 1))).andExpect(status().isOk());
    }

    @Test
    void stateChangingRequestsWithoutTheTokenAreRejected() throws Exception {
        Product lamp = createProduct("Forged Lamp", "10.00", 5);

        expectCsrfFailure(post("/api/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemJson(lamp.getId(), 1)));
        expectCsrfFailure(put("/api/cart/items/{id}", lamp.getId()).contentType(MediaType.APPLICATION_JSON).content(quantityJson(2)));
        expectCsrfFailure(delete("/api/cart/items/{id}", lamp.getId()));
        expectCsrfFailure(post("/api/checkout").header(IDEMPOTENCY_KEY_HEADER, "7a1e5b6e-6d4b-4d7e-9b7a-2f1a0c9d8e7f"));
        mockMvc.perform(getWithSession("/api/cart")).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void aWrongTokenIsRejected() throws Exception {
        Product lamp = createProduct("Wrong Token Lamp", "10.00", 5);

        mockMvc.perform(post("/api/cart/items").contentType(MediaType.APPLICATION_JSON).content(addItemJson(lamp.getId(), 1))
                        .cookie(sessionCookie(), new Cookie(TestBrowser.CSRF_COOKIE, user.browser().cookie(TestBrowser.CSRF_COOKIE)))
                        .header(TestBrowser.CSRF_HEADER, "forged-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_FAILED"));
    }

    @Test
    void readsDoNotNeedTheToken() throws Exception {
        mockMvc.perform(get("/api/cart").cookie(sessionCookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/orders").cookie(sessionCookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/products")).andExpect(status().isOk());
    }

    @Test
    void loginAndRegisterAreProtectedAgainstLoginCsrf() throws Exception {
        // A foreign page could otherwise log the victim into the attacker's account and watch what they do.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson(user.email(), TEST_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_FAILED"));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentialsJson("csrf-" + System.nanoTime() + "@example.com", TEST_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_FAILED"));
    }

    @Test
    void theCsrfEndpointHandsOutAReadableTokenCookie() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value(TestBrowser.CSRF_HEADER))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .stringValues("Set-Cookie", org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.startsWith(TestBrowser.CSRF_COOKIE + "="),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("HttpOnly"))))));
    }

    private void expectCsrfFailure(MockHttpServletRequestBuilder request) throws Exception {
        // Session cookie present (the browser always sends it), CSRF header missing (a forged cross-site request).
        mockMvc.perform(request.cookie(sessionCookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_FAILED"));
    }

    private Cookie sessionCookie() {
        return new Cookie(TestBrowser.SESSION_COOKIE, user.browser().cookie(TestBrowser.SESSION_COOKIE));
    }
}
