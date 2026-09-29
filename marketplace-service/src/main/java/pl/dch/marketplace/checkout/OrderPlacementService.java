package pl.dch.marketplace.checkout;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * Checkout transaction 1: turns the session's cart into an order that waits for its payment.
 * <p>
 * The cart only says <em>what</em> and <em>how many</em>; prices and stock are always re-read here.
 * In one short transaction the order is stored as {@code PAYMENT_PENDING}, its stock is taken and
 * the cart is cleared. If any step fails, nothing changes. No remote call happens here.
 */
@Service
public class OrderPlacementService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public OrderPlacementService(CartRepository cartRepository,
                                 ProductRepository productRepository,
                                 OrderRepository orderRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * Returns the existing order when this checkout key was already used by the session.
     * <p>
     * Concurrency:
     * <ul>
     *   <li>the cart row is locked first, so checkouts and cart edits of the <em>same</em> session run one
     *       after another; a duplicate request waits and then finds the first request's order by its key
     *       (the unique constraint on {@code (session_id, checkout_idempotency_key)} stays as the last guard);</li>
     *   <li>checkouts of <em>different</em> sessions are not serialized. They compete only for product stock,
     *       protected by {@code Product.@Version}: the loser's commit fails with an optimistic lock conflict,
     *       translated by {@link CheckoutService} to 409 {@code CONCURRENT_STOCK_CHANGE}.</li>
     * </ul>
     */
    @Transactional
    public PlacedOrder placeOrder(SessionId sessionId, UUID checkoutIdempotencyKey) {
        Optional<Cart> lockedCart = cartRepository.findBySessionIdForUpdate(sessionId.value());

        Optional<Order> existing = orderRepository.findBySessionIdAndCheckoutIdempotencyKey(
                sessionId.value(), checkoutIdempotencyKey);
        if (existing.isPresent()) {
            return PlacedOrder.existing(existing.get());
        }

        Cart cart = lockedCart
                .filter(found -> !found.isEmpty())
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

        Order order = orderRepository.save(Order.create(sessionId.value(), checkoutIdempotencyKey, lines, now()));
        cart.clear();
        return PlacedOrder.created(order);
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> findByCheckoutKey(SessionId sessionId, UUID checkoutIdempotencyKey) {
        return orderRepository.findBySessionIdAndCheckoutIdempotencyKey(sessionId.value(), checkoutIdempotencyKey)
                .map(OrderResponse::from);
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

    /**
     * @param created false when the checkout key was already known and the existing order is returned
     */
    public record PlacedOrder(OrderResponse order, UUID paymentIdempotencyKey, boolean created) {

        static PlacedOrder created(Order order) {
            return new PlacedOrder(OrderResponse.from(order), order.getPaymentIdempotencyKey(), true);
        }

        static PlacedOrder existing(Order order) {
            return new PlacedOrder(OrderResponse.from(order), order.getPaymentIdempotencyKey(), false);
        }
    }
}
