package pl.dch.marketplace.cart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;

/**
 * Shopping cart of one anonymous session. Items are only reachable through the cart,
 * so every modification goes through the methods below (aggregate root).
 */
@Entity
@Table(name = "cart")
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true, updatable = false)
    private UUID sessionId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
        // for JPA
    }

    public Cart(UUID sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    }

    /**
     * Adds the quantity to an existing line for the product, or creates a new line.
     */
    public void addItem(Long productId, int quantity) {
        Optional<CartItem> existing = findItem(productId);
        if (existing.isPresent()) {
            existing.get().increaseQuantity(quantity);
        } else {
            items.add(new CartItem(this, productId, quantity));
        }
    }

    /**
     * The line quantity {@link #addItem} would produce, validated against the same limits,
     * without modifying the cart.
     */
    public int quantityAfterAdding(Long productId, int quantity) {
        return CartItem.sumQuantities(quantityOf(productId), quantity);
    }

    public void changeQuantity(Long productId, int quantity) {
        CartItem item = findItem(productId)
                .orElseThrow(() -> new MarketplaceException(ErrorCode.CART_ITEM_NOT_FOUND,
                        "Product %d is not in the cart".formatted(productId)));
        item.changeQuantity(quantity);
    }

    /**
     * Removing a product that is not in the cart is a no-op (idempotent DELETE).
     */
    public void removeItem(Long productId) {
        items.removeIf(item -> item.getProductId().equals(productId));
    }

    public void clear() {
        items.clear();
    }

    public int quantityOf(Long productId) {
        return findItem(productId).map(CartItem::getQuantity).orElse(0);
    }

    public Set<Long> productIds() {
        return items.stream()
                .map(CartItem::getProductId)
                .collect(Collectors.toSet());
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Long getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    private Optional<CartItem> findItem(Long productId) {
        return items.stream()
                .filter(item -> item.getProductId().equals(productId))
                .findFirst();
    }
}
