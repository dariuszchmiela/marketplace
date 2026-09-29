package pl.dch.marketplace.checkout;

import java.time.Duration;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.order.OrderStatus;

/**
 * Business metrics of the checkout orchestration.
 * <ul>
 *   <li>{@code marketplace.checkout.total{result}} — outcomes. {@code result} is one of the bounded {@link Result}
 *       values, never an order/payment id;</li>
 *   <li>{@code marketplace.checkout.duration{result}} — the whole orchestration (transaction 1 + payment call +
 *       transaction 2) as the shopper experiences it, with a percentile histogram for p50/p95/p99;</li>
 *   <li>{@code marketplace.checkout.step.duration{step}} — each step separately. It answers "where did the time go?":
 *       {@code place_order} and {@code apply_outcome} are the two short database transactions,
 *       {@code payment_call} is the remote call that runs with no transaction and no connection held.</li>
 * </ul>
 */
@Component
public class CheckoutMetrics {

    public enum Result {
        PAID, PAYMENT_FAILED, PAYMENT_UNKNOWN,
        /** Same Idempotency-Key again: the existing order was returned, payment-service was not called. */
        REPLAYED,
        /** Optimistic lock conflict on product stock (409 CONCURRENT_STOCK_CHANGE). */
        STOCK_CONFLICT,
        /** Business rejection before anything was ordered: empty cart, insufficient stock, unavailable product. */
        REJECTED,
        /** Anything unexpected. */
        ERROR;

        static Result of(OrderStatus status) {
            return switch (status) {
                case PAID -> PAID;
                case PAYMENT_FAILED -> PAYMENT_FAILED;
                case PAYMENT_UNKNOWN, PAYMENT_PENDING -> PAYMENT_UNKNOWN;
                case NEW -> ERROR;
            };
        }

        static Result of(RuntimeException failure) {
            if (failure instanceof MarketplaceException marketplace) {
                return marketplace.getCode() == ErrorCode.CONCURRENT_STOCK_CHANGE ? STOCK_CONFLICT : REJECTED;
            }
            return ERROR;
        }
    }

    public enum Step { PLACE_ORDER, PAYMENT_CALL, APPLY_OUTCOME }

    static final String TOTAL = "marketplace.checkout.total";
    static final String DURATION = "marketplace.checkout.duration";
    static final String STEP_DURATION = "marketplace.checkout.step.duration";
    static final String STOCK_CONFLICT = "marketplace.stock.conflict";

    private final MeterRegistry registry;
    private final Counter stockConflicts;

    public CheckoutMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Counters registered up front so every result exists (value 0) from the first scrape: a series that first appears
        // with a non-zero value is invisible to rate()/increase(). Timers stay lazy (each one carries histogram buckets).
        for (Result result : Result.values()) {
            counter(result);
        }
        this.stockConflicts = Counter.builder(STOCK_CONFLICT)
                .description("Checkouts that lost an optimistic-lock race for product stock")
                .register(registry);
    }

    void record(Result result, Duration elapsed) {
        counter(result).increment();
        timer(result).record(elapsed);
        if (result == Result.STOCK_CONFLICT) {
            stockConflicts.increment();
        }
    }

    <T> T step(Step step, Supplier<T> work) {
        return Timer.builder(STEP_DURATION)
                .description("Duration of one checkout step")
                .tag("step", step.name().toLowerCase())
                .register(registry)
                .record(work);
    }

    private Counter counter(Result result) {
        return Counter.builder(TOTAL)
                .description("Checkout attempts by outcome")
                .tag("result", result.name())
                .register(registry);
    }

    private Timer timer(Result result) {
        return Timer.builder(DURATION)
                .description("Checkout orchestration duration (both transactions and the payment call)")
                .tag("result", result.name())
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);
    }
}
