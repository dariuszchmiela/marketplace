package pl.dch.marketplace.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Lines are fetched together with the orders to avoid N+1 queries when mapping to DTOs.
     */
    @EntityGraph(attributePaths = "lines")
    List<Order> findAllBySessionIdOrderByCreatedAtDescIdDesc(UUID sessionId);

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findByIdAndSessionId(Long id, UUID sessionId);

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findBySessionIdAndCheckoutIdempotencyKey(UUID sessionId, UUID checkoutIdempotencyKey);

    /**
     * {@code SELECT ... FOR UPDATE}: the checkout flow and reconciliation may try to apply a payment
     * result to the same order at the same time. The row lock serializes them, so the second one
     * sees the status written by the first.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
}
