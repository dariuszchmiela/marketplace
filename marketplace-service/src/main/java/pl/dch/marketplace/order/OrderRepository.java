package pl.dch.marketplace.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Lines are fetched together with the orders to avoid N+1 queries when mapping to DTOs.
     */
    @EntityGraph(attributePaths = "lines")
    List<Order> findAllBySessionIdOrderByCreatedAtDescIdDesc(UUID sessionId);

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findByIdAndSessionId(Long id, UUID sessionId);
}
