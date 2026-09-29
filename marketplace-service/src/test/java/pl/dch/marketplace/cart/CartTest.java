package pl.dch.marketplace.cart;

import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.dch.marketplace.cart.CartItem.MAX_QUANTITY;

/**
 * The quantity limit is a domain invariant: it holds for every way a line quantity can change.
 */
class CartTest {

    private final Cart cart = new Cart(UUID.randomUUID());

    @Test
    void acceptsMaximumQuantityForNewItem() {
        cart.addItem(1L, MAX_QUANTITY);

        assertThat(cart.quantityOf(1L)).isEqualTo(MAX_QUANTITY);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, MAX_QUANTITY + 1, Integer.MAX_VALUE})
    void rejectsInvalidQuantityForNewItem(int quantity) {
        assertInvalidQuantity(() -> cart.addItem(1L, quantity));
        assertThat(cart.isEmpty()).isTrue();
    }

    @Test
    void changesQuantityUpToMaximum() {
        cart.addItem(1L, 1);

        cart.changeQuantity(1L, MAX_QUANTITY);

        assertThat(cart.quantityOf(1L)).isEqualTo(MAX_QUANTITY);
    }

    @Test
    void rejectsChangingQuantityAboveMaximum() {
        cart.addItem(1L, 5);

        assertInvalidQuantity(() -> cart.changeQuantity(1L, MAX_QUANTITY + 1));
        assertThat(cart.quantityOf(1L)).isEqualTo(5);
    }

    @Test
    void repeatedAdditionsMayReachMaximumExactly() {
        cart.addItem(1L, MAX_QUANTITY - 1);

        cart.addItem(1L, 1);

        assertThat(cart.quantityOf(1L)).isEqualTo(MAX_QUANTITY);
    }

    @Test
    void rejectsRepeatedAdditionsThatTogetherExceedMaximum() {
        cart.addItem(1L, 600);

        assertInvalidQuantity(() -> cart.addItem(1L, 401));
        assertInvalidQuantity(() -> cart.quantityAfterAdding(1L, 401));
        assertThat(cart.quantityOf(1L)).isEqualTo(600);
    }

    @Test
    void sumThatWouldOverflowIntIsRejectedInsteadOfWrappingAround() {
        cart.addItem(1L, MAX_QUANTITY);

        assertInvalidQuantity(() -> cart.addItem(1L, Integer.MAX_VALUE));
        assertThat(cart.quantityOf(1L)).isEqualTo(MAX_QUANTITY);
    }

    @Test
    void restoringItemsOfAFailedOrderMergesWithTheCartAndIsCappedAtMaximum() {
        cart.addItem(1L, MAX_QUANTITY - 3);

        cart.restoreItem(1L, 5);
        cart.restoreItem(2L, 2);
        cart.restoreItem(1L, 1);

        assertThat(cart.quantityOf(1L)).isEqualTo(MAX_QUANTITY);
        assertThat(cart.quantityOf(2L)).isEqualTo(2);
    }

    @Test
    void quantityAfterAddingDoesNotModifyCart() {
        cart.addItem(1L, 2);

        assertThat(cart.quantityAfterAdding(1L, 3)).isEqualTo(5);
        assertThat(cart.quantityAfterAdding(2L, 3)).isEqualTo(3);
        assertThat(cart.quantityOf(1L)).isEqualTo(2);
        assertThat(cart.quantityOf(2L)).isZero();
    }

    private static void assertInvalidQuantity(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(MarketplaceException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_QUANTITY);
    }
}
