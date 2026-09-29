package pl.dch.marketplace.cart;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Concurrency strategy for carts: <strong>every mutation locks the cart row</strong>
 * ({@code SELECT … FOR UPDATE}) for the rest of its short transaction. Mutations of one cart (several
 * browser tabs, a checkout, a payment failure putting items back) therefore run one after another and
 * see each other's committed result: no lost updates, no duplicate lines. A cart belongs to one shopper,
 * so contention is tiny and waiting a few milliseconds is better than failing with a conflict.
 * Reads ({@link #findBySessionId}) do not lock.
 * <p>
 * Lock order used everywhere: order row → cart row → product rows (ascending id). Never the other way round.
 */
public interface CartRepository extends JpaRepository<Cart, Long> {

    /**
     * Cart and its items in one query (left join: an empty cart is still found; distinct: one Cart per row set).
     * An explicit fetch join instead of {@code @EntityGraph(attributePaths = "items")}: the ad-hoc entity graph made
     * Spring Data first look up a <em>named</em> graph "Cart.findBySessionId" on every call, which Hibernate answers
     * with an internally thrown and caught IllegalArgumentException (found with JFR, see production-diagnostics.md).
     */
    @Query("select distinct c from Cart c left join fetch c.items i where c.sessionId = :sessionId order by i.id")
    Optional<Cart> findBySessionId(@Param("sessionId") UUID sessionId);

    /**
     * No fetch join: PostgreSQL cannot {@code FOR UPDATE} the nullable side of an outer join.
     * The items are loaded lazily after the lock is held.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.sessionId = :sessionId")
    Optional<Cart> findBySessionIdForUpdate(@Param("sessionId") UUID sessionId);

    /**
     * Creating a cart must not race either: two first requests of a new session would both INSERT and one
     * would fail on the unique session_id. {@code ON CONFLICT DO NOTHING} makes creation idempotent.
     */
    @Modifying
    @Query(value = "insert into cart (session_id) values (:sessionId) on conflict (session_id) do nothing",
            nativeQuery = true)
    int insertIfAbsent(@Param("sessionId") UUID sessionId);

    /** The session's cart, created if missing, locked until the end of the current transaction. */
    default Cart lockOrCreate(UUID sessionId) {
        insertIfAbsent(sessionId);
        return findBySessionIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalStateException("Cart of session " + sessionId + " vanished"));
    }
}
