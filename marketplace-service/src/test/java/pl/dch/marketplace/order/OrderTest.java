package pl.dch.marketplace.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void lineTotalIsUnitPriceTimesQuantity() {
        OrderLine line = new OrderLine(1L, "Keyboard", new BigDecimal("349.99"), 3);

        assertThat(line.getLineTotal()).isEqualByComparingTo("1049.97");
    }

    @Test
    void totalIsSumOfLineTotals() {
        Order order = Order.create(SESSION, List.of(
                new OrderLine(1L, "Keyboard", new BigDecimal("349.99"), 2),
                new OrderLine(2L, "Cable", new BigDecimal("0.10"), 3)), NOW);

        assertThat(order.getTotal()).isEqualByComparingTo("700.28");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    void orderRequiresAtLeastOneLine() {
        assertThatThrownBy(() -> Order.create(SESSION, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linesCannotBeModifiedFromOutside() {
        Order order = Order.create(SESSION, List.of(new OrderLine(1L, "Keyboard", BigDecimal.ONE, 1)), NOW);

        assertThatThrownBy(() -> order.getLines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
