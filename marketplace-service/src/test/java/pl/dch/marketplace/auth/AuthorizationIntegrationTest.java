package pl.dch.marketplace.auth;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.TestBrowser;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may do what. Authentication says who the caller is; authorization here is resource ownership: every cart and
 * order query is scoped by the owner key taken from the authenticated principal, never from the request.
 */
class AuthorizationIntegrationTest extends IntegrationTestBase {

    @Test
    void theCatalogIsPublic() throws Exception {
        Product lamp = createProduct("Public Lamp", "10.00", 5);

        mockMvc.perform(get("/api/products")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/{id}", lamp.getId())).andExpect(status().isOk());
    }

    @Test
    void cartCheckoutOrdersAndMeRequireAuthentication() throws Exception {
        TestBrowser anonymous = new TestBrowser(mockMvc);
        anonymous.refreshCsrfToken();   // a valid CSRF token is not a login

        for (String path : new String[] {"/api/cart", "/api/orders", "/api/orders/1", "/api/auth/me"}) {
            assertThat(httpStatus(anonymous.perform(get(path)))).as(path).isEqualTo(401);
        }
        MvcResult checkout = anonymous.perform(post("/api/checkout").header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID()));
        assertThat(httpStatus(checkout)).isEqualTo(401);
        assertThat(JsonPath.<String>read(checkout.getResponse().getContentAsString(), "$.code")).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(checkout.getResponse().getContentType()).startsWith("application/json");   // no HTML, no redirect
        assertThat(httpStatus(anonymous.perform(post("/api/auth/logout")))).isEqualTo(401);
    }

    @Test
    void theLegacyXSessionIdHeaderGrantsNothing() throws Exception {
        Product lamp = createProduct("Header Lamp", "10.00", 5);
        addToCart(user, lamp.getId(), 2);

        // Anonymous caller who guessed/stole user A's owner key: still 401.
        mockMvc.perform(get("/api/cart").header("X-Session-Id", session)).andExpect(status().isUnauthorized());
        // Another user sending A's owner key: sees their own (empty) cart, the header is simply ignored.
        TestUser other = signUp();
        mockMvc.perform(as(other, get("/api/cart")).header("X-Session-Id", session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void cartsAreIsolatedBetweenUsers() throws Exception {
        Product lamp = createProduct("Isolated Lamp", "10.00", 5);
        addToCart(user, lamp.getId(), 3);
        TestUser other = signUp();

        mockMvc.perform(as(other, get("/api/cart"))).andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(getWithSession("/api/cart")).andExpect(jsonPath("$.items[0].quantity").value(3));
    }

    @Test
    void anotherUsersOrderIsNotFoundRatherThanForbidden() throws Exception {
        Product lamp = createProduct("Owned Lamp", "10.00", 5);
        addToCart(user, lamp.getId(), 1);
        long orderId = orderId(mockMvc.perform(checkout()).andExpect(status().isCreated()).andReturn());
        TestUser other = signUp();

        // 404, not 403: the other user must not even learn that order #id exists.
        mockMvc.perform(as(other, get("/api/orders/{id}", orderId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(as(other, post("/api/orders/{id}/reconcile-payment", orderId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(other, get("/api/orders"))).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(getWithSession("/api/orders/{id}", orderId)).andExpect(status().isOk());
    }

    @Test
    void theSameIdempotencyKeyOfTwoUsersCreatesTwoIndependentOrders() throws Exception {
        Product lamp = createProduct("Shared Key Lamp", "10.00", 5);
        TestUser other = signUp();
        addToCart(user, lamp.getId(), 1);
        addToCart(other, lamp.getId(), 1);
        UUID sameKey = UUID.randomUUID();

        long first = orderId(mockMvc.perform(checkout(sameKey)).andExpect(status().isCreated()).andReturn());
        long second = orderId(mockMvc.perform(as(other, post("/api/checkout").header(IDEMPOTENCY_KEY_HEADER, sameKey)))
                .andExpect(status().isCreated()).andReturn());

        // Checkout idempotency is scoped by owner (unique (session_id, checkout_idempotency_key)), not global.
        assertThat(second).isNotEqualTo(first);
        assertThat(stockOf(lamp)).isEqualTo(3);
    }

    @Test
    void responsesNeverExposeTheInternalOwnerKey() throws Exception {
        Product lamp = createProduct("Secret Key Lamp", "10.00", 5);
        addToCart(user, lamp.getId(), 1);
        mockMvc.perform(checkout()).andReturn();

        for (String path : new String[] {"/api/auth/me", "/api/cart", "/api/orders"}) {
            mockMvc.perform(getWithSession(path)).andExpect(content().string(not(containsString(session))));
        }
    }

    @Test
    void apiDocumentationIsPublicAndNoLongerDescribesAnXSessionIdParameter() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("X-Session-Id"))));
    }

    private static int httpStatus(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static long orderId(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
