package pl.dch.marketplace;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pl.dch.marketplace.product.Product;
import pl.dch.marketplace.product.ProductRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Full application against a real PostgreSQL (Testcontainers) with Flyway migrations applied.
 * Tests isolate themselves by using a fresh session id and their own products,
 * so no database cleanup between tests is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {

    protected static final String SESSION_HEADER = "X-Session-Id";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected final String session = UUID.randomUUID().toString();

    protected Product createProduct(String name, String price, int availableQuantity) {
        return productRepository.save(new Product(name, name + " description", new BigDecimal(price), availableQuantity));
    }

    protected int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getAvailableQuantity();
    }

    protected MockHttpServletRequestBuilder getWithSession(String url, Object... vars) {
        return get(url, vars).header(SESSION_HEADER, session);
    }

    protected MockHttpServletRequestBuilder postWithSession(String url, String json) {
        return post(url).header(SESSION_HEADER, session).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected MockHttpServletRequestBuilder putWithSession(String url, String json, Object... vars) {
        return put(url, vars).header(SESSION_HEADER, session).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected MockHttpServletRequestBuilder deleteWithSession(String url, Object... vars) {
        return delete(url, vars).header(SESSION_HEADER, session);
    }

    protected static String addItemJson(long productId, int quantity) {
        return """
                {"productId": %d, "quantity": %d}""".formatted(productId, quantity);
    }

    protected static String quantityJson(int quantity) {
        return """
                {"quantity": %d}""".formatted(quantity);
    }
}
