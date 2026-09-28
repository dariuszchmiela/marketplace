package pl.dch.marketplace.order;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.session.SessionId;

/**
 * Read side of orders. Orders are scoped to the session that created them; an order of
 * another session is reported as not found rather than forbidden, so ids cannot be probed.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public List<OrderResponse> findAll(SessionId sessionId) {
        return orderRepository.findAllBySessionIdOrderByCreatedAtDescIdDesc(sessionId.value()).stream()
                .map(OrderResponse::from)
                .toList();
    }

    public OrderResponse findById(SessionId sessionId, long orderId) {
        return orderRepository.findByIdAndSessionId(orderId, sessionId.value())
                .map(OrderResponse::from)
                .orElseThrow(() -> new MarketplaceException(ErrorCode.ORDER_NOT_FOUND,
                        "Order %d not found".formatted(orderId)));
    }
}
