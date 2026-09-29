package pl.dch.marketplace.cart;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.product.Product;
import pl.dch.marketplace.product.ProductRepository;
import pl.dch.marketplace.session.SessionId;

@Service
@Transactional
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    public CartService(CartRepository cartRepository, ProductRepository productRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
    }

    /**
     * A session without a stored cart simply has an empty one; nothing is persisted on read.
     */
    @Transactional(readOnly = true)
    public CartResponse getCart(SessionId sessionId) {
        return cartRepository.findBySessionId(sessionId.value())
                .map(this::toResponse)
                .orElseGet(() -> new CartResponse(List.of(), BigDecimal.ZERO));
    }

    public CartResponse addItem(SessionId sessionId, long productId, int quantity) {
        CartItem.requireValidQuantity(quantity);
        Product product = findProduct(productId);
        Cart cart = cartRepository.findBySessionId(sessionId.value())
                .orElseGet(() -> cartRepository.save(new Cart(sessionId.value())));

        // The quantity limit is checked before stock: exceeding it is a client error, not a stock conflict.
        ensureStock(product, cart.quantityAfterAdding(productId, quantity));
        cart.addItem(productId, quantity);
        return toResponse(cart);
    }

    public CartResponse updateItemQuantity(SessionId sessionId, long productId, int quantity) {
        CartItem.requireValidQuantity(quantity);
        Cart cart = cartRepository.findBySessionId(sessionId.value())
                .orElseThrow(() -> cartItemNotFound(productId));
        Product product = findProduct(productId);

        ensureStock(product, quantity);
        cart.changeQuantity(productId, quantity);
        return toResponse(cart);
    }

    public CartResponse removeItem(SessionId sessionId, long productId) {
        return cartRepository.findBySessionId(sessionId.value())
                .map(cart -> {
                    cart.removeItem(productId);
                    return toResponse(cart);
                })
                .orElseGet(() -> new CartResponse(List.of(), BigDecimal.ZERO));
    }

    private Product findProduct(long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new MarketplaceException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product %d not found".formatted(productId)));
    }

    /**
     * Early feedback for the shopper only. This is not a reservation: stock can change
     * before checkout, which validates it again.
     */
    private static void ensureStock(Product product, int requestedQuantity) {
        if (!product.hasStock(requestedQuantity)) {
            throw new MarketplaceException(ErrorCode.INSUFFICIENT_STOCK,
                    "Only %d item(s) of '%s' available, requested %d"
                            .formatted(product.getAvailableQuantity(), product.getName(), requestedQuantity));
        }
    }

    private static MarketplaceException cartItemNotFound(long productId) {
        return new MarketplaceException(ErrorCode.CART_ITEM_NOT_FOUND,
                "Product %d is not in the cart".formatted(productId));
    }

    private CartResponse toResponse(Cart cart) {
        // One query for all products instead of one per cart item, then O(1) lookups by id.
        Map<Long, Product> productsById = productRepository.findAllById(cart.productIds()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<CartResponse.CartItemResponse> items = cart.getItems().stream()
                .map(item -> toItemResponse(item, productsById.get(item.getProductId())))
                .toList();

        BigDecimal total = items.stream()
                .map(CartResponse.CartItemResponse::lineTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartResponse(items, total);
    }

    private static CartResponse.CartItemResponse toItemResponse(CartItem item, Product product) {
        if (product == null) {
            return new CartResponse.CartItemResponse(
                    item.getProductId(), false, null, null, item.getQuantity(), null, 0);
        }
        BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartResponse.CartItemResponse(
                item.getProductId(),
                true,
                product.getName(),
                product.getPrice(),
                item.getQuantity(),
                lineTotal,
                product.getAvailableQuantity());
    }
}
