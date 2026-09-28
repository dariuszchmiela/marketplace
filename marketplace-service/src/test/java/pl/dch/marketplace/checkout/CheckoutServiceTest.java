package pl.dch.marketplace.checkout;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pl.dch.marketplace.cart.Cart;
import pl.dch.marketplace.cart.CartRepository;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.order.Order;
import pl.dch.marketplace.order.OrderRepository;
import pl.dch.marketplace.order.OrderResponse;
import pl.dch.marketplace.order.OrderStatus;
import pl.dch.marketplace.product.Product;
import pl.dch.marketplace.product.ProductRepository;
import pl.dch.marketplace.session.SessionId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.dch.marketplace.product.TestProducts.product;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutServiceTest {

    private static final SessionId SESSION = new SessionId(UUID.randomUUID());

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    private CheckoutService checkoutService;

    private final Product keyboard = product(1L, "Keyboard", "349.99", 10);
    private final Product mouse = product(2L, "Mouse", "129.50", 3);
    private final Product cable = product(3L, "Cable", "0.10", 100);

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(cartRepository, productRepository, orderRepository);
        givenCatalog(keyboard, mouse, cable);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void rejectsCheckoutWhenSessionHasNoCart() {
        when(cartRepository.findBySessionId(SESSION.value())).thenReturn(Optional.empty());

        assertCheckoutFailsWith(ErrorCode.CART_EMPTY);
    }

    @Test
    void rejectsCheckoutOfEmptyCart() {
        givenCart(new Cart(SESSION.value()));

        assertCheckoutFailsWith(ErrorCode.CART_EMPTY);
    }

    @Test
    void rejectsCheckoutWhenProductNoLongerExists() {
        Cart cart = new Cart(SESSION.value());
        cart.addItem(1L, 1);
        cart.addItem(99L, 1);
        givenCart(cart);

        assertCheckoutFailsWith(ErrorCode.PRODUCT_UNAVAILABLE);
        assertThat(keyboard.getAvailableQuantity()).isEqualTo(10);
        assertThat(cart.isEmpty()).isFalse();
    }

    @Test
    void rejectsCheckoutWhenStockIsInsufficientWithoutTouchingOtherProducts() {
        Cart cart = new Cart(SESSION.value());
        cart.addItem(1L, 2);
        cart.addItem(2L, 4);
        givenCart(cart);

        assertCheckoutFailsWith(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(keyboard.getAvailableQuantity()).isEqualTo(10);
        assertThat(mouse.getAvailableQuantity()).isEqualTo(3);
        assertThat(cart.isEmpty()).isFalse();
    }

    @Test
    void calculatesTotalFromCurrentBackendPricesWithExactDecimalArithmetic() {
        Cart cart = new Cart(SESSION.value());
        cart.addItem(1L, 2);
        cart.addItem(2L, 3);
        cart.addItem(3L, 3);
        givenCart(cart);

        OrderResponse order = checkoutService.checkout(SESSION);

        // 2 * 349.99 + 3 * 129.50 + 3 * 0.10 = 699.98 + 388.50 + 0.30
        // (with double, 3 * 0.10 would already be 0.30000000000000004)
        assertThat(order.total()).isEqualByComparingTo("1088.78");
        assertThat(order.lines()).extracting(OrderResponse.OrderLineResponse::lineTotal)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("699.98"),
                        new BigDecimal("388.50"),
                        new BigDecimal("0.30"));
    }

    @Test
    void successfulCheckoutCreatesOrderDecreasesStockAndClearsCart() {
        Cart cart = new Cart(SESSION.value());
        cart.addItem(1L, 2);
        cart.addItem(2L, 3);
        givenCart(cart);

        OrderResponse response = checkoutService.checkout(SESSION);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        Order order = saved.getValue();
        assertThat(order.getSessionId()).isEqualTo(SESSION.value());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(order.getLines()).hasSize(2);

        assertThat(response.lines()).first().satisfies(line -> {
            assertThat(line.productId()).isEqualTo(1L);
            assertThat(line.productName()).isEqualTo("Keyboard");
            assertThat(line.unitPrice()).isEqualByComparingTo("349.99");
            assertThat(line.quantity()).isEqualTo(2);
        });
        assertThat(response.total()).isEqualByComparingTo("1088.48");

        assertThat(keyboard.getAvailableQuantity()).isEqualTo(8);
        assertThat(mouse.getAvailableQuantity()).isZero();
        assertThat(cart.isEmpty()).isTrue();
    }

    private void assertCheckoutFailsWith(ErrorCode expected) {
        assertThatThrownBy(() -> checkoutService.checkout(SESSION))
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(expected);
        verify(orderRepository, never()).save(any());
    }

    private void givenCart(Cart cart) {
        when(cartRepository.findBySessionId(SESSION.value())).thenReturn(Optional.of(cart));
    }

    private void givenCatalog(Product... products) {
        List<Product> catalog = List.of(products);
        when(productRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return catalog.stream().filter(product -> ids.contains(product.getId())).toList();
        });
    }
}
