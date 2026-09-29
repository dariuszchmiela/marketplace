package pl.dch.marketplace.checkout;


import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.product.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckoutFlowIntegrationTest extends IntegrationTestBase {

    @Test
    void seededProductsAreAvailableAfterStartup() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Mechanical Keyboard")));
    }

    @Test
    void productsToCartToCheckoutToCreatedOrder() throws Exception {
        Product lamp = createProduct("Desk Lamp", "79.90", 10);
        Product chair = createProduct("Office Chair", "999.00", 2);

        mockMvc.perform(get("/api/products/{id}", lamp.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(79.90))
                .andExpect(jsonPath("$.availableQuantity").value(10));

        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(lamp.getId(), 1)))
                .andExpect(status().isOk());
        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(chair.getId(), 1)))
                .andExpect(status().isOk());
        mockMvc.perform(putWithSession("/api/cart/items/{id}", quantityJson(3), lamp.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.total").value(1238.70));

        MvcResult checkout = mockMvc.perform(checkout())
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentId").isNotEmpty())
                .andExpect(jsonPath("$.total").value(1238.70))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].productName").value("Desk Lamp"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(79.90))
                .andExpect(jsonPath("$.lines[0].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(239.70))
                .andReturn();
        long orderId = ((Number) JsonPath.read(checkout.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(checkout.getResponse().getHeader("Location")).isEqualTo("/api/orders/" + orderId);

        // Order is persisted and readable by its owner.
        mockMvc.perform(getWithSession("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1238.70))
                .andExpect(jsonPath("$.lines", hasSize(2)));
        mockMvc.perform(getWithSession("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(orderId));

        // Stock decreased, cart cleared.
        assertThat(stockOf(lamp)).isEqualTo(7);
        assertThat(stockOf(chair)).isEqualTo(1);
        mockMvc.perform(getWithSession("/api/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value(0));

        // A new checkout attempt (new key) finds an empty cart instead of creating a second order.
        mockMvc.perform(checkout())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CART_EMPTY"));
    }

    @Test
    void orderPriceIsTheSnapshotFromCheckoutTime() throws Exception {
        Product lamp = createProduct("Snapshot Lamp", "50.00", 5);
        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(lamp.getId(), 2)))
                .andExpect(status().isOk());
        MvcResult checkout = mockMvc.perform(checkout())
                .andExpect(status().isCreated())
                .andReturn();
        long orderId = ((Number) JsonPath.read(checkout.getResponse().getContentAsString(), "$.id")).longValue();

        jdbcTemplate.update("UPDATE product SET price = 75.00 WHERE id = ?", lamp.getId());

        mockMvc.perform(getWithSession("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(50.00))
                .andExpect(jsonPath("$.total").value(100.00));
    }

    @Test
    void failedCheckoutRollsBackAndLeavesStockOrdersAndCartUntouched() throws Exception {
        Product lamp = createProduct("Rollback Lamp", "10.00", 5);
        Product chair = createProduct("Rollback Chair", "20.00", 5);
        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(lamp.getId(), 2)))
                .andExpect(status().isOk());
        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(chair.getId(), 3)))
                .andExpect(status().isOk());

        // Someone else bought chairs in the meantime: the cart is not a reservation.
        jdbcTemplate.update("UPDATE product SET available_quantity = 1 WHERE id = ?", chair.getId());

        mockMvc.perform(checkout())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(PAYMENT_SERVICE.postRequests()).isEmpty();
        assertThat(stockOf(lamp)).isEqualTo(5);
        assertThat(stockOf(chair)).isEqualTo(1);
        mockMvc.perform(getWithSession("/api/orders"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(getWithSession("/api/cart"))
                .andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void ordersAreVisibleOnlyToTheUserWhoCreatedThem() throws Exception {
        Product lamp = createProduct("Private Lamp", "10.00", 5);
        mockMvc.perform(postWithSession("/api/cart/items", addItemJson(lamp.getId(), 1)))
                .andExpect(status().isOk());
        MvcResult checkout = mockMvc.perform(checkout())
                .andExpect(status().isCreated())
                .andReturn();
        long orderId = ((Number) JsonPath.read(checkout.getResponse().getContentAsString(), "$.id")).longValue();

        TestUser otherUser = signUp();
        mockMvc.perform(as(otherUser, get("/api/orders/{id}", orderId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(as(otherUser, get("/api/orders")))
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
