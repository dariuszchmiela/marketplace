package pl.dch.marketplace.checkout;

import java.net.URI;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.dch.marketplace.order.OrderResponse;
import pl.dch.marketplace.session.SessionId;

@RestController
@RequestMapping("/api/checkout")
@Tag(name = "Checkout")
class CheckoutController {

    private final CheckoutService checkoutService;

    CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    /**
     * Creates an order from the current cart. Responds 201 with the order and its location.
     */
    @PostMapping
    ResponseEntity<OrderResponse> checkout(SessionId sessionId) {
        OrderResponse order = checkoutService.checkout(sessionId);
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }
}
