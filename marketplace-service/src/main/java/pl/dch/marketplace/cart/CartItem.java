package pl.dch.marketplace.cart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;

/**
 * A product reference and a quantity. Deliberately holds no price or stock information:
 * those always come from {@code Product}.
 */
@Entity
@Table(name = "cart_item")
public class CartItem {

    /**
     * Upper bound for the quantity of one cart line. Enforced by the domain for every resulting
     * quantity (so repeated additions cannot exceed it) and reused by the request DTOs for early
     * Bean Validation feedback.
     */
    public static final int MAX_QUANTITY = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    protected CartItem() {
        // for JPA
    }

    CartItem(Cart cart, Long productId, int quantity) {
        this.cart = cart;
        this.productId = productId;
        this.quantity = requireValidQuantity(quantity);
    }

    /**
     * Accepts a {@code long} so that a sum of two quantities is checked before it is narrowed to {@code int}.
     */
    static int requireValidQuantity(long quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new MarketplaceException(ErrorCode.INVALID_QUANTITY,
                    "Quantity must be between 1 and %d, got %d".formatted(MAX_QUANTITY, quantity));
        }
        return (int) quantity;
    }

    /**
     * Validates the added quantity and the resulting sum. The sum is computed as {@code long}, so it cannot overflow.
     */
    static int sumQuantities(int current, int added) {
        requireValidQuantity(added);
        return requireValidQuantity((long) current + added);
    }

    void changeQuantity(int quantity) {
        this.quantity = requireValidQuantity(quantity);
    }

    void increaseQuantity(int added) {
        this.quantity = sumQuantities(quantity, added);
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}
