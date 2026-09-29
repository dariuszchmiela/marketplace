package pl.dch.marketplace.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * An order and its payment state. The status only changes through the {@code mark*} methods,
 * which enforce the transitions defined by {@link OrderStatus#canTransitionTo}.
 */
@Entity
@Table(name = "orders")
public class Order {

    /** A single currency is assumed everywhere (see docs/architecture.md); it is only named towards payment-service. */
    public static final String CURRENCY = "PLN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Client-supplied key of the checkout attempt that created this order. */
    @Column(name = "checkout_idempotency_key", updatable = false)
    private UUID checkoutIdempotencyKey;

    /** Our key towards payment-service. Every payment call for this order uses it. */
    @Column(name = "payment_idempotency_key", updatable = false)
    private UUID paymentIdempotencyKey;

    /** payment-service id, known once payment-service has returned a payment. */
    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_failure_reason", length = 32)
    private PaymentFailureReason paymentFailureReason;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("id")
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {
        // for JPA
    }

    private Order(UUID sessionId, UUID checkoutIdempotencyKey, List<OrderLine> lines, Instant createdAt) {
        this.sessionId = sessionId;
        this.checkoutIdempotencyKey = checkoutIdempotencyKey;
        this.paymentIdempotencyKey = UUID.randomUUID();
        this.status = OrderStatus.PAYMENT_PENDING;
        this.createdAt = createdAt;
        lines.forEach(this::addLine);
        this.total = calculateTotal(lines);
    }

    /**
     * A new order waits for its payment ({@link OrderStatus#PAYMENT_PENDING}) and gets its own
     * payment idempotency key.
     */
    public static Order create(UUID sessionId, UUID checkoutIdempotencyKey, List<OrderLine> lines, Instant createdAt) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(checkoutIdempotencyKey, "checkoutIdempotencyKey must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("order must have at least one line");
        }
        return new Order(sessionId, checkoutIdempotencyKey, lines, createdAt);
    }

    static BigDecimal calculateTotal(List<OrderLine> lines) {
        return lines.stream()
                .map(OrderLine::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void markPaid(String paymentId) {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        transitionTo(OrderStatus.PAID);
        this.paymentId = paymentId;
    }

    public void markPaymentDeclined(String paymentId) {
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        transitionTo(OrderStatus.PAYMENT_FAILED);
        this.paymentId = paymentId;
        this.paymentFailureReason = PaymentFailureReason.DECLINED;
    }

    public void markPaymentNotProcessed() {
        transitionTo(OrderStatus.PAYMENT_FAILED);
        this.paymentFailureReason = PaymentFailureReason.NOT_PROCESSED;
    }

    public void markPaymentUnknown() {
        transitionTo(OrderStatus.PAYMENT_UNKNOWN);
    }

    public boolean isAwaitingPaymentResult() {
        return status.isAwaitingPaymentResult();
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Order %d cannot change status from %s to %s".formatted(id, status, target));
        }
        this.status = target;
    }

    private void addLine(OrderLine line) {
        line.assignTo(this);
        lines.add(line);
    }

    public Long getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCheckoutIdempotencyKey() {
        return checkoutIdempotencyKey;
    }

    public UUID getPaymentIdempotencyKey() {
        return paymentIdempotencyKey;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public PaymentFailureReason getPaymentFailureReason() {
        return paymentFailureReason;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
