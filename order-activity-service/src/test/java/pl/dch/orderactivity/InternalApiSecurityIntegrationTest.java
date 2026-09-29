package pl.dch.orderactivity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP API is internal. Simulation is enabled here (as in local demos) to prove that even then it is not
 * reachable anonymously.
 */
@SpringBootTest(properties = {"order-activity.security.api-token=test-activity-token",
        "order-activity.simulation.enabled=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalApiSecurityIntegrationTest {

    private static final String TOKEN = "Bearer test-activity-token";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void projectionReadNeedsTheInternalToken() throws Exception {
        mockMvc.perform(get("/api/order-activity/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_API_TOKEN"));
        mockMvc.perform(get("/api/order-activity/1").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_TOKEN"));
        mockMvc.perform(get("/api/order-activity/999999999").header("Authorization", TOKEN))
                .andExpect(status().isNotFound());   // authenticated; the order is simply not projected
    }

    @Test
    void simulationEndpointsAreNeverAnonymous() throws Exception {
        String rule = """
                {"match": "OrderPaid", "mode": "PERMANENT"}""";
        mockMvc.perform(post("/api/simulation/failures").contentType(MediaType.APPLICATION_JSON).content(rule))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/simulation/failures"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/simulation/failures").header("Authorization", TOKEN))
                .andExpect(status().isNoContent());
    }
}
