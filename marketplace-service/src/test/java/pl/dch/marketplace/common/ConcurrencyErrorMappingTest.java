package pl.dch.marketplace.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The centralized safety net: an expected race that no service translated more specifically is a
 * 409 "try again", never a generic 500, and JPA/SQL details are not exposed.
 */
class ConcurrencyErrorMappingTest {

    @RestController
    static class RacingController {

        @GetMapping("/optimistic")
        void optimistic() {
            throw new ObjectOptimisticLockingFailureException("pl.dch.marketplace.cart.Cart", 1L);
        }

        @GetMapping("/lock")
        void lock() {
            throw new CannotAcquireLockException("deadlock detected");
        }

        @GetMapping("/bug")
        void bug() {
            throw new IllegalStateException("a real bug");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RacingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void optimisticLockConflictIsA409() throws Exception {
        mockMvc.perform(get("/optimistic"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.message", containsString("try again")))
                .andExpect(jsonPath("$.message", not(containsString("Cart"))));
    }

    @Test
    void lockFailureIsA409() throws Exception {
        mockMvc.perform(get("/lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedBugsStay500() throws Exception {
        mockMvc.perform(get("/bug"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
