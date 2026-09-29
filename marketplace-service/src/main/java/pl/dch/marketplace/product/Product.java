package pl.dch.marketplace.product;

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    /**
     * Optimistic locking version: Hibernate increments it on every update and adds
     * {@code WHERE version = ?} to the UPDATE. If another transaction changed the row since we read it,
     * the UPDATE matches 0 rows and the transaction fails instead of overwriting that change (no lost update).
     * <p>
     * Stock strategy:
     * <ul>
     *   <li><b>purchase</b> ({@link #decreaseStock}, checkout transaction 1): read–check–write through this
     *       entity, protected by this version. Two buyers of the last unit both read stock 1; the second
     *       commit fails and checkout answers 409 {@code CONCURRENT_STOCK_CHANGE}. Stock can never go negative
     *       (domain check here + {@code CHECK (available_quantity >= 0)} in the database).</li>
     *   <li><b>compensation</b> ({@code ProductRepository.increaseStock}, failed payment): a single atomic
     *       {@code UPDATE … + ?} that also bumps this version. It needs no read, so it cannot lose updates and
     *       never has to fail; bumping the version makes a checkout that read the old stock fail instead of
     *       silently overwriting the returned units.</li>
     * </ul>
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected Product() {
        // for JPA
    }

    public Product(String name, String description, BigDecimal price, int availableQuantity) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.price = Objects.requireNonNull(price, "price must not be null");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("availableQuantity must not be negative");
        }
        this.availableQuantity = availableQuantity;
    }

    public boolean hasStock(int quantity) {
        return availableQuantity >= quantity;
    }

    public void decreaseStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (!hasStock(quantity)) {
            throw new MarketplaceException(ErrorCode.INSUFFICIENT_STOCK,
                    "Insufficient stock for product %d: requested %d, available %d"
                            .formatted(id, quantity, availableQuantity));
        }
        availableQuantity -= quantity;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public long getVersion() {
        return version;
    }
}
