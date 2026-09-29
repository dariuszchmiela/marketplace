package pl.dch.marketplace.checkout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.order.Order;
import pl.dch.marketplace.order.OrderLine;
import pl.dch.marketplace.order.OrderResponse;
import pl.dch.marketplace.payment.PaymentClient;
import pl.dch.marketplace.payment.PaymentOutcome;
import pl.dch.marketplace.payment.RemotePayment;
import pl.dch.marketplace.session.SessionId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orchestration only; the database and HTTP behaviour are covered by the integration tests.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    private static final SessionId SESSION = new SessionId(UUID.randomUUID());
    private static final UUID CHECKOUT_KEY = UUID.randomUUID();

    @Mock
    private OrderPlacementService orderPlacement;

    @Mock
    private PaymentClient paymentClient;

    @Mock
    private OrderPaymentUpdater orderPaymentUpdater;

    private CheckoutService checkoutService;

    private Order order;
    private OrderResponse orderResponse;

    @BeforeEach
    void setUp() {
        order = Order.create(SESSION.value(), CHECKOUT_KEY,
                List.of(new OrderLine(1L, "Keyboard", new BigDecimal("349.99"), 2)), Instant.now());
        ReflectionTestUtils.setField(order, "id", 5L);
        orderResponse = OrderResponse.from(order);
        checkoutService = new CheckoutService(orderPlacement, paymentClient, orderPaymentUpdater);
    }

    @Test
    void placesOrderThenPaysWithTheOrdersPaymentKeyThenAppliesTheOutcome() {
        when(orderPlacement.placeOrder(SESSION, CHECKOUT_KEY))
                .thenReturn(new OrderPlacementService.PlacedOrder(orderResponse, order.getPaymentIdempotencyKey(), true));
        PaymentOutcome outcome = new PaymentOutcome.Succeeded("pay-1");
        when(paymentClient.pay(any(), any())).thenReturn(outcome);
        when(orderPaymentUpdater.applyOutcome(anyLong(), any())).thenReturn(orderResponse);

        CheckoutService.CheckoutResult result = checkoutService.checkout(SESSION, CHECKOUT_KEY, "DECLINED");

        assertThat(result.created()).isTrue();
        InOrder steps = inOrder(orderPlacement, paymentClient, orderPaymentUpdater);
        steps.verify(orderPlacement).placeOrder(SESSION, CHECKOUT_KEY);
        steps.verify(paymentClient).pay(
                new RemotePayment.Request(5L, new BigDecimal("699.98"), "PLN", order.getPaymentIdempotencyKey()),
                "DECLINED");
        steps.verify(orderPaymentUpdater).applyOutcome(5L, outcome);
    }

    @Test
    void replayedCheckoutReturnsTheExistingOrderWithoutPayingAgain() {
        when(orderPlacement.placeOrder(SESSION, CHECKOUT_KEY))
                .thenReturn(new OrderPlacementService.PlacedOrder(orderResponse, order.getPaymentIdempotencyKey(), false));

        CheckoutService.CheckoutResult result = checkoutService.checkout(SESSION, CHECKOUT_KEY, null);

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isEqualTo(orderResponse);
        verifyNoInteractions(paymentClient, orderPaymentUpdater);
    }

    @Test
    void concurrentDuplicateThatLostTheRaceReturnsTheWinnersOrder() {
        when(orderPlacement.placeOrder(SESSION, CHECKOUT_KEY))
                .thenThrow(new DataIntegrityViolationException("uk_orders_session_checkout_key"));
        when(orderPlacement.findByCheckoutKey(SESSION, CHECKOUT_KEY)).thenReturn(Optional.of(orderResponse));

        CheckoutService.CheckoutResult result = checkoutService.checkout(SESSION, CHECKOUT_KEY, null);

        assertThat(result.created()).isFalse();
        verifyNoInteractions(paymentClient, orderPaymentUpdater);
    }

    @Test
    void duplicateThatSawTheCartAlreadyEmptiedByTheWinnerReturnsTheWinnersOrder() {
        // The winner committed between our key lookup and our cart read (found by the smoke test).
        when(orderPlacement.placeOrder(SESSION, CHECKOUT_KEY))
                .thenThrow(new MarketplaceException(ErrorCode.CART_EMPTY, "Cart is empty"));
        when(orderPlacement.findByCheckoutKey(SESSION, CHECKOUT_KEY)).thenReturn(Optional.of(orderResponse));

        CheckoutService.CheckoutResult result = checkoutService.checkout(SESSION, CHECKOUT_KEY, null);

        assertThat(result.order()).isEqualTo(orderResponse);
        assertThat(result.created()).isFalse();
    }

    @Test
    void failureWithoutAnExistingOrderIsRethrown() {
        MarketplaceException failure = new MarketplaceException(ErrorCode.CART_EMPTY, "Cart is empty");
        when(orderPlacement.placeOrder(SESSION, CHECKOUT_KEY)).thenThrow(failure);
        when(orderPlacement.findByCheckoutKey(SESSION, CHECKOUT_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checkoutService.checkout(SESSION, CHECKOUT_KEY, null)).isSameAs(failure);
        verifyNoInteractions(paymentClient);
    }
}
