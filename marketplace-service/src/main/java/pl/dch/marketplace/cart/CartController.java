package pl.dch.marketplace.cart;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.dch.marketplace.session.SessionId;

/**
 * Every mutation returns the full, recalculated cart so the client never has to
 * compute totals itself.
 */
@RestController
@RequestMapping("/api/cart")
@Tag(name = "Cart")
class CartController {

    private final CartService cartService;

    CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    CartResponse getCart(SessionId sessionId) {
        return cartService.getCart(sessionId);
    }

    @PostMapping("/items")
    CartResponse addItem(SessionId sessionId, @Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(sessionId, request.productId(), request.quantity());
    }

    @PutMapping("/items/{productId}")
    CartResponse updateItem(SessionId sessionId,
                            @PathVariable long productId,
                            @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateItemQuantity(sessionId, productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    CartResponse removeItem(SessionId sessionId, @PathVariable long productId) {
        return cartService.removeItem(sessionId, productId);
    }
}
