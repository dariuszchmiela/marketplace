import { useEffect, useState } from 'react'
import { api, errorMessage, isAbortError } from './api/client'
import type { Cart, Order } from './api/types'
import { CartPanel } from './components/CartPanel'
import { ErrorMessage } from './components/ErrorMessage'
import { OrderConfirmation } from './components/OrderConfirmation'
import { ProductList } from './components/ProductList'

type View = { kind: 'shop' } | { kind: 'confirmation'; order: Order }

const EMPTY_CART: Cart = { items: [], total: 0 }

export function App() {
  const [view, setView] = useState<View>({ kind: 'shop' })
  // The cart lives here because both the product list (add) and the cart panel use it.
  // It is always replaced with the backend's response - never recalculated locally.
  const [cart, setCart] = useState<Cart | null>(null)
  const [cartError, setCartError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    api
      .getCart(controller.signal)
      .then(setCart)
      .catch((error: unknown) => {
        if (!isAbortError(error)) {
          setCartError(errorMessage(error))
        }
      })
    return () => controller.abort()
  }, [])

  async function addToCart(productId: number) {
    setCart(await api.addToCart(productId, 1))
  }

  async function updateQuantity(productId: number, quantity: number) {
    setCart(await api.updateCartItem(productId, quantity))
  }

  async function removeItem(productId: number) {
    setCart(await api.removeCartItem(productId))
  }

  async function checkout() {
    const order = await api.checkout()
    // Checkout empties the cart on the backend in the same transaction that creates the order.
    setCart(EMPTY_CART)
    setView({ kind: 'confirmation', order })
  }

  const itemCount = cart?.items.reduce((sum, item) => sum + item.quantity, 0) ?? 0

  return (
    <div className="app">
      <header className="app-header">
        <h1>Marketplace Interview Lab</h1>
        <span className="muted">
          {itemCount} item{itemCount === 1 ? '' : 's'} in cart
        </span>
      </header>

      {view.kind === 'confirmation' ? (
        <OrderConfirmation order={view.order} onContinueShopping={() => setView({ kind: 'shop' })} />
      ) : (
        <main className="shop">
          <ProductList onAddToCart={addToCart} />
          <aside>
            {cartError && <ErrorMessage message={cartError} />}
            {!cart && !cartError && <p className="muted">Loading cart…</p>}
            {cart && (
              <CartPanel cart={cart} onUpdateQuantity={updateQuantity} onRemove={removeItem} onCheckout={checkout} />
            )}
          </aside>
        </main>
      )}
    </div>
  )
}
