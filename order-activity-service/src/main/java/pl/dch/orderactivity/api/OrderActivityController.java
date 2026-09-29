package pl.dch.orderactivity.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.dch.orderactivity.activity.OrderActivityRepository;

/**
 * Read-only view of the projection (for demos and the smoke test). Eventually consistent: it may lag behind
 * the marketplace. 404 means "not seen yet", not "does not exist".
 */
@RestController
@RequestMapping("/api/order-activity")
class OrderActivityController {

    record OrderActivityResponse(long orderId, String currentStatus, BigDecimal total, String currency,
                                 UUID lastEventId, int lastSequence, Instant updatedAt,
                                 List<OrderActivityRepository.Entry> history) {
    }

    private final OrderActivityRepository activities;

    OrderActivityController(OrderActivityRepository activities) {
        this.activities = activities;
    }

    @GetMapping("/{orderId}")
    ResponseEntity<OrderActivityResponse> find(@PathVariable long orderId) {
        return activities.find(orderId)
                .map(activity -> ResponseEntity.ok(new OrderActivityResponse(activity.orderId(), activity.currentStatus(),
                        activity.total(), activity.currency(), activity.lastEventId(), activity.lastSequence(),
                        activity.updatedAt(), activities.history(orderId))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
