package pl.dch.marketplace.cart;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.product.Product;
import pl.dch.marketplace.product.ProductRepository;
import pl.dch.marketplace.session.SessionId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static pl.dch.marketplace.product.TestProducts.product;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceTest {

    private static final SessionId SESSION = new SessionId(UUID.randomUUID());

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductRepository productRepository;

    private CartService cartService;

    private final Product keyboard = product(1L, "Keyboard", "349.99", 10);
    private final Product mouse = product(2L, "Mouse", "129.50", 3);
    // More stock than the cart quantity limit, so the limit (not stock) is what gets hit.
    private final Product cable = product(3L, "Cable", "9.99", CartItem.MAX_QUANTITY * 5);

    @BeforeEach
    void setUp() {
        cartService = new CartService(cartRepository, productRepository);
        List<Product> catalog = List.of(keyboard, mouse, cable);
        when(productRepository.findById(1L)).thenReturn(Optional.of(keyboard));
        when(productRepository.findById(2L)).thenReturn(Optional.of(mouse));
        when(productRepository.findById(3L)).thenReturn(Optional.of(cable));
        when(productRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return catalog.stream().filter(product -> ids.contains(product.getId())).toList();
        });
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void addsProductToNewCartAndReturnsCurrentPrices() {
        when(cartRepository.findBySessionId(SESSION.value())).thenReturn(Optional.empty());

        CartResponse response = cartService.addItem(SESSION, 1L, 2);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(1L);
            assertThat(item.productName()).isEqualTo("Keyboard");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.unitPrice()).isEqualByComparingTo("349.99");
            assertThat(item.lineTotal()).isEqualByComparingTo("699.98");
        });
        assertThat(response.total()).isEqualByComparingTo("699.98");
    }

    @Test
    void addingSameProductAgainIncreasesQuantity() {
        cartWith(1L, 2);

        CartResponse response = cartService.addItem(SESSION, 1L, 3);

        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(5));
    }

    @Test
    void updatesQuantityAndRecalculatesTotal() {
        Cart cart = cartWith(1L, 1);
        cart.addItem(2L, 1);

        CartResponse response = cartService.updateItemQuantity(SESSION, 2L, 3);

        assertThat(cart.quantityOf(2L)).isEqualTo(3);
        // 349.99 + 3 * 129.50
        assertThat(response.total()).isEqualByComparingTo("738.49");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveQuantityWhenAdding(int quantity) {
        assertThatThrownBy(() -> cartService.addItem(SESSION, 1L, quantity))
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_QUANTITY);
        verifyNoInteractions(cartRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    void rejectsNonPositiveQuantityWhenUpdating(int quantity) {
        Cart cart = cartWith(1L, 2);

        assertThatThrownBy(() -> cartService.updateItemQuantity(SESSION, 1L, quantity))
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThat(cart.quantityOf(1L)).isEqualTo(2);
    }

    @Test
    void acceptsMaximumQuantityWhenAddingAndUpdating() {
        Cart cart = cartWith(3L, 1);

        cartService.updateItemQuantity(SESSION, 3L, CartItem.MAX_QUANTITY);
        assertThat(cart.quantityOf(3L)).isEqualTo(CartItem.MAX_QUANTITY);

        cartService.removeItem(SESSION, 3L);
        CartResponse response = cartService.addItem(SESSION, 3L, CartItem.MAX_QUANTITY);
        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(CartItem.MAX_QUANTITY));
    }

    @Test
    void rejectsQuantityAboveMaximumWhenAddingAndUpdating() {
        Cart cart = cartWith(3L, 1);

        assertThatThrownBy(() -> cartService.addItem(SESSION, 3L, CartItem.MAX_QUANTITY + 1))
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThatThrownBy(() -> cartService.updateItemQuantity(SESSION, 3L, CartItem.MAX_QUANTITY + 1))
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThat(cart.quantityOf(3L)).isEqualTo(1);
    }

    @Test
    void rejectsRepeatedAdditionsThatTogetherExceedMaximumEvenWhenStockWouldAllowIt() {
        Cart cart = cartWith(3L, 600);

        cartService.addItem(SESSION, 3L, 400);
        assertThat(cart.quantityOf(3L)).isEqualTo(CartItem.MAX_QUANTITY);

        assertThatThrownBy(() -> cartService.addItem(SESSION, 3L, 1))
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_QUANTITY);
        assertThat(cart.quantityOf(3L)).isEqualTo(CartItem.MAX_QUANTITY);
    }

    @Test
    void rejectsAddingMoreThanAvailableStockIncludingWhatIsAlreadyInCart() {
        Cart cart = cartWith(2L, 2);

        assertThatThrownBy(() -> cartService.addItem(SESSION, 2L, 2))
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(cart.quantityOf(2L)).isEqualTo(2);
    }

    @Test
    void rejectsUnknownProduct() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.addItem(SESSION, 99L, 1))
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    void updatingProductThatIsNotInCartFails() {
        cartWith(1L, 1);

        assertThatThrownBy(() -> cartService.updateItemQuantity(SESSION, 2L, 1))
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND);
    }

    @Test
    void removesItem() {
        Cart cart = cartWith(1L, 1);
        cart.addItem(2L, 1);

        CartResponse response = cartService.removeItem(SESSION, 1L);

        assertThat(response.items()).extracting(CartResponse.CartItemResponse::productId).containsExactly(2L);
        assertThat(response.total()).isEqualByComparingTo("129.50");
    }

    @Test
    void cartViewMarksProductsThatNoLongerExistAndExcludesThemFromTotal() {
        Cart cart = cartWith(1L, 1);
        cart.addItem(42L, 1);

        CartResponse response = cartService.getCart(SESSION);

        assertThat(response.items()).filteredOn(item -> !item.productExists())
                .singleElement()
                .satisfies(item -> assertThat(item.productId()).isEqualTo(42L));
        assertThat(response.total()).isEqualByComparingTo("349.99");
    }

    @Test
    void emptyCartForUnknownSessionWithoutPersistingAnything() {
        when(cartRepository.findBySessionId(SESSION.value())).thenReturn(Optional.empty());

        CartResponse response = cartService.getCart(SESSION);

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private Cart cartWith(long productId, int quantity) {
        Cart cart = new Cart(SESSION.value());
        cart.addItem(productId, quantity);
        when(cartRepository.findBySessionId(SESSION.value())).thenReturn(Optional.of(cart));
        return cart;
    }
}
