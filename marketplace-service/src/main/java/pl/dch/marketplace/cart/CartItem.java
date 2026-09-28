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
        requireValidQuantity(quantity);
        this.cart = cart;
        this.productId = productId;
        this.quantity = quantity;
    }

    static void requireValidQuantity(int quantity) {
        if (quantity <= 0) {
            throw new MarketplaceException(ErrorCode.INVALID_QUANTITY,
                    "Quantity must be positive, got " + quantity);
        }
    }

    void changeQuantity(int quantity) {
        requireValidQuantity(quantity);
        this.quantity = quantity;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}
