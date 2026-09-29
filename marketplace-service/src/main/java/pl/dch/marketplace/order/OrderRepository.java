package pl.dch.marketplace.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads fetch the order lines in the same query (fetch join), so mapping to DTOs needs no further queries (no N+1)
 * and works without open-session-in-view. Explicit JPQL instead of {@code @EntityGraph(attributePaths = "lines")}:
 * the ad-hoc entity graph made Spring Data first look up a named graph ("Order.<method name>") on every call, which
 * Hibernate answers with an internally thrown and caught IllegalArgumentException (found with JFR). Every order has
 * at least one line, but a left join keeps the queries correct regardless; distinct = one Order per id.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Newest first; lines by id (the collection's {@code @OrderBy}). */
    @Query("""
            select distinct o from Order o left join fetch o.lines l
            where o.sessionId = :sessionId
            order by o.createdAt desc, o.id desc, l.id""")
    List<Order> findAllBySessionIdOrderByCreatedAtDescIdDesc(@Param("sessionId") UUID sessionId);

    @Query("select distinct o from Order o left join fetch o.lines l where o.id = :id and o.sessionId = :sessionId order by l.id")
    Optional<Order> findByIdAndSessionId(@Param("id") Long id, @Param("sessionId") UUID sessionId);

    @Query("""
            select distinct o from Order o left join fetch o.lines l
            where o.sessionId = :sessionId and o.checkoutIdempotencyKey = :checkoutIdempotencyKey
            order by l.id""")
    Optional<Order> findBySessionIdAndCheckoutIdempotencyKey(@Param("sessionId") UUID sessionId,
                                                             @Param("checkoutIdempotencyKey") UUID checkoutIdempotencyKey);

    /**
     * {@code SELECT ... FOR UPDATE}: the checkout flow and reconciliation may try to apply a payment
     * result to the same order at the same time. The row lock serializes them, so the second one
     * sees the status written by the first.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
}
