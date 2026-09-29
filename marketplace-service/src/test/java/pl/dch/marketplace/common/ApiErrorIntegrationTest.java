package pl.dch.marketplace.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.cart.CartItem;
import pl.dch.marketplace.product.Product;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every failure uses the same {@link ApiError} body with a stable code.
 */
class ApiErrorIntegrationTest extends IntegrationTestBase {

    @Test
    void missingSessionHeader() throws Exception {
        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MISSING_SESSION_ID"))
                .andExpect(jsonPath("$.path").value("/api/cart"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void sessionHeaderThatIsNotUuid() throws Exception {
        mockMvc.perform(get("/api/cart").header(SESSION_HEADER, "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_ID"));
    }

    @Test
    void invalidQuantityIsRejectedByBeanValidation() throws Exception {
        Product product = createProduct("Validated Lamp", "10.00", 5);

        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(product.getId(), 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"));
    }

    @Test
    void missingFieldsAreReportedPerField() throws Exception {
        mockMvc.perform(postWithSession("/api/cart/items", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(contains("productId", "quantity")));
    }

    @Test
    void malformedJson() throws Exception {
        mockMvc.perform(postWithSession("/api/cart/items", "{\"productId\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unknownProduct() throws Exception {
        mockMvc.perform(get("/api/products/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(999_999, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void repeatedAdditionsAboveCartQuantityLimitAreRejectedAsInvalidQuantity() throws Exception {
        Product product = createProduct("Plentiful Lamp", "10.00", CartItem.MAX_QUANTITY * 5);

        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(product.getId(), CartItem.MAX_QUANTITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(CartItem.MAX_QUANTITY));
        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(product.getId(), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
        mockMvc.perform(getWithSession("/api/cart"))
                .andExpect(jsonPath("$.items[0].quantity").value(CartItem.MAX_QUANTITY));
    }

    @Test
    void quantityAboveLimitInSingleRequestIsRejectedByBeanValidation() throws Exception {
        Product product = createProduct("Bulk Lamp", "10.00", CartItem.MAX_QUANTITY * 5);

        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(product.getId(), CartItem.MAX_QUANTITY + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"));
    }

    @Test
    void addingMoreThanAvailableStock() throws Exception {
        Product product = createProduct("Scarce Lamp", "10.00", 2);

        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(product.getId(), 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void nonNumericPathVariable() throws Exception {
        mockMvc.perform(get("/api/products/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unknownEndpointAndWrongMethodUseTheSameErrorShape() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void removingProductThatIsNotInCartIsIdempotent() throws Exception {
        mockMvc.perform(deleteWithSession("/api/cart/items/{id}", 123))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }
}
