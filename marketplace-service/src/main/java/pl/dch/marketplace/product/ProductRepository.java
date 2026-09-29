package pl.dch.marketplace.product;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByOrderByIdAsc();

    /**
     * Returns stock of an order whose payment failed. A single atomic UPDATE (read-modify-write in
     * the database) cannot lose a concurrent change and cannot fail with an optimistic lock conflict,
     * so a failed payment is always closed. The version is bumped so that a checkout that read the
     * old stock still detects the change (without the bump, that checkout's entity UPDATE would write back
     * its stale stock value and the returned units would be lost). Returns 0 if the product was deleted.
     * <p>
     * Why not the entity + {@code @Version} like a purchase? A compensation must not fail on a conflict:
     * it runs while a payment result is being recorded, and there is no user who could "try again".
     * Bypasses the persistence context: callers must not hold a loaded {@link Product} in the same transaction.
     */
    @Modifying
    @Query("update Product p set p.availableQuantity = p.availableQuantity + :quantity, p.version = p.version + 1"
            + " where p.id = :id")
    int increaseStock(@Param("id") Long id, @Param("quantity") int quantity);
}
