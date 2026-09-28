package pl.dch.marketplace.order;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.dch.marketplace.session.SessionId;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders")
class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    List<OrderResponse> findAll(SessionId sessionId) {
        return orderService.findAll(sessionId);
    }

    @GetMapping("/{id}")
    OrderResponse findById(SessionId sessionId, @PathVariable long id) {
        return orderService.findById(sessionId, id);
    }
}
