package pl.dch.marketplace.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID CHECKOUT_KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void lineTotalIsUnitPriceTimesQuantity() {
        OrderLine line = new OrderLine(1L, "Keyboard", new BigDecimal("349.99"), 3);

        assertThat(line.getLineTotal()).isEqualByComparingTo("1049.97");
    }

    @Test
    void totalIsSumOfLineTotals() {
        Order order = Order.create(SESSION, CHECKOUT_KEY, List.of(
                new OrderLine(1L, "Keyboard", new BigDecimal("349.99"), 2),
                new OrderLine(2L, "Cable", new BigDecimal("0.10"), 3)), NOW);

        assertThat(order.getTotal()).isEqualByComparingTo("700.28");
    }

    @Test
    void newOrderWaitsForPaymentAndHasItsOwnPaymentKey() {
        Order order = newOrder();
        Order other = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getCheckoutIdempotencyKey()).isEqualTo(CHECKOUT_KEY);
        assertThat(order.getPaymentIdempotencyKey()).isNotNull().isNotEqualTo(other.getPaymentIdempotencyKey());
        assertThat(order.getPaymentId()).isNull();
        assertThat(order.getPaymentFailureReason()).isNull();
    }

    @Test
    void orderRequiresAtLeastOneLine() {
        assertThatThrownBy(() -> Order.create(SESSION, CHECKOUT_KEY, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linesCannotBeModifiedFromOutside() {
        Order order = newOrder();

        assertThatThrownBy(() -> order.getLines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void pendingOrderBecomesPaid() {
        Order order = newOrder();

        order.markPaid("pay-1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaymentId()).isEqualTo("pay-1");
        assertThat(order.isAwaitingPaymentResult()).isFalse();
    }

    @Test
    void pendingOrderIsDeclined() {
        Order order = newOrder();

        order.markPaymentDeclined("pay-1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.getPaymentFailureReason()).isEqualTo(PaymentFailureReason.DECLINED);
        assertThat(order.getPaymentId()).isEqualTo("pay-1");
    }

    @Test
    void pendingOrderFailsWhenPaymentWasNotProcessed() {
        Order order = newOrder();

        order.markPaymentNotProcessed();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(order.getPaymentFailureReason()).isEqualTo(PaymentFailureReason.NOT_PROCESSED);
        assertThat(order.getPaymentId()).isNull();
    }

    @Test
    void unknownPaymentIsResolvedToPaidByReconciliation() {
        Order order = newOrder();

        order.markPaymentUnknown();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
        assertThat(order.isAwaitingPaymentResult()).isTrue();

        order.markPaid("pay-1");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void unknownPaymentCanTurnOutDeclined() {
        Order order = newOrder();
        order.markPaymentUnknown();

        order.markPaymentDeclined("pay-1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    void finalStatesCannotChange() {
        Order paid = newOrder();
        paid.markPaid("pay-1");
        Order failed = newOrder();
        failed.markPaymentNotProcessed();

        assertThatThrownBy(() -> paid.markPaymentDeclined("pay-2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(paid::markPaymentUnknown).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.markPaid("pay-2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(failed::markPaymentUnknown).isInstanceOf(IllegalStateException.class);
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    void unknownCannotBecomeUnknownAgain() {
        Order order = newOrder();
        order.markPaymentUnknown();

        assertThatThrownBy(order::markPaymentUnknown).isInstanceOf(IllegalStateException.class);
    }

    /** The full transition table in one place. */
    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void allowedTransitions(OrderStatus from) {
        Set<OrderStatus> expected = switch (from) {
            case PAYMENT_PENDING -> EnumSet.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED, OrderStatus.PAYMENT_UNKNOWN);
            case PAYMENT_UNKNOWN -> EnumSet.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED);
            case NEW, PAID, PAYMENT_FAILED -> EnumSet.noneOf(OrderStatus.class);
        };

        Set<OrderStatus> allowed = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus to : OrderStatus.values()) {
            if (from.canTransitionTo(to)) {
                allowed.add(to);
            }
        }

        assertThat(allowed).isEqualTo(expected);
    }

    private static Order newOrder() {
        return Order.create(SESSION, CHECKOUT_KEY, List.of(new OrderLine(1L, "Keyboard", BigDecimal.ONE, 1)), NOW);
    }
}
