package pl.dch.orderactivity.simulation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev/test only (bean exists only with {@code order-activity.simulation.enabled=true}): register deterministic
 * failures for the smoke test, e.g. {@code {"match":"OrderPaid","mode":"TRANSIENT","times":2}}.
 */
@RestController
@RequestMapping("/api/simulation/failures")
@ConditionalOnProperty(name = "order-activity.simulation.enabled", havingValue = "true")
class SimulationController {

    record FailureRequest(String match, String mode, Integer times) {
    }

    private final FailureSimulator simulator;

    SimulationController(FailureSimulator simulator) {
        this.simulator = simulator;
    }

    @PostMapping
    ResponseEntity<Void> register(@RequestBody FailureRequest request) {
        if (request.match() == null || request.match().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if ("PERMANENT".equalsIgnoreCase(request.mode())) {
            simulator.failPermanently(request.match());
        } else {
            simulator.failTransiently(request.match(), request.times() == null ? 1 : request.times());
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    ResponseEntity<Void> clear() {
        simulator.reset();
        return ResponseEntity.noContent().build();
    }
}
