package pl.dch.marketplace.checkout;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.dch.marketplace.cart.Cart;
import pl.dch.marketplace.cart.CartItem;
import pl.dch.marketplace.cart.CartRepository;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.order.Order;
import pl.dch.marketplace.order.OrderLine;
import pl.dch.marketplace.order.OrderRepository;
import pl.dch.marketplace.order.OrderResponse;
import pl.dch.marketplace.product.Product;
import pl.dch.marketplace.product.ProductRepository;
import pl.dch.marketplace.session.SessionId;

/**
 * Turns the session's cart into an order.
 * <p>
 * The cart only says <em>what</em> and <em>how many</em>; prices and stock are always re-read
 * from the database here. Everything runs in one transaction: if any step fails, no stock is
 * decreased, no order is stored and the cart is left untouched.
 */
@Service
public class CheckoutService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public CheckoutService(CartRepository cartRepository,
                           ProductRepository productRepository,
                           OrderRepository orderRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse checkout(SessionId sessionId) {
        Cart cart = cartRepository.findBySessionId(sessionId.value())
                .filter(existing -> !existing.isEmpty())
                .orElseThrow(() -> new MarketplaceException(ErrorCode.CART_EMPTY, "Cart is empty"));

        // One query for all products, then O(1) lookups instead of searching a list per cart item.
        Map<Long, Product> productsById = productRepository.findAllById(cart.productIds()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        // Pure transformation: validates every item and snapshots the current price. No side effects.
        List<OrderLine> lines = cart.getItems().stream()
                .map(item -> toOrderLine(item, productsById))
                .toList();

        // Side effects are kept out of the stream on purpose. Stock is only touched after
        // every line was validated, so a failing item cannot leave others half-processed.
        for (CartItem item : cart.getItems()) {
            productsById.get(item.getProductId()).decreaseStock(item.getQuantity());
        }

        Order order = orderRepository.save(Order.create(sessionId.value(), lines, now()));
        cart.clear();
        return OrderResponse.from(order);
    }

    private static OrderLine toOrderLine(CartItem item, Map<Long, Product> productsById) {
        Product product = productsById.get(item.getProductId());
        if (product == null) {
            throw new MarketplaceException(ErrorCode.PRODUCT_UNAVAILABLE,
                    "Product %d is no longer available".formatted(item.getProductId()));
        }
        if (!product.hasStock(item.getQuantity())) {
            throw new MarketplaceException(ErrorCode.INSUFFICIENT_STOCK,
                    "Only %d item(s) of '%s' available, requested %d"
                            .formatted(product.getAvailableQuantity(), product.getName(), item.getQuantity()));
        }
        return new OrderLine(product.getId(), product.getName(), product.getPrice(), item.getQuantity());
    }

    /**
     * PostgreSQL stores microseconds; truncating keeps the returned value equal to the stored one.
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
