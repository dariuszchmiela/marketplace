import { useEffect, useRef, useState } from 'react'
import { api, ApiError, errorMessage, isAbortError } from './api/client'
import type { Cart, Order } from './api/types'
import { CartPanel } from './components/CartPanel'
import { ErrorMessage } from './components/ErrorMessage'
import { OrderConfirmation } from './components/OrderConfirmation'
import { ProductList } from './components/ProductList'

type View = { kind: 'shop' } | { kind: 'confirmation'; order: Order }

/**
 * Whether a failed checkout request may have reached the backend without us seeing the answer
 * (network error, 5xx, gateway/proxy error). Retrying it must then reuse the same idempotency key,
 * so the backend returns the order it may already have created instead of creating a second one.
 */
function mayHaveBeenProcessed(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 0 || error.status >= 500
}

// The backend rejected the checkout because stock changed (e.g. another shopper bought the last unit at the
// same moment). Nothing was ordered; the shopper needs fresh stock numbers before trying again.
const STOCK_CHANGED_CODES = new Set(['CONCURRENT_STOCK_CHANGE', 'INSUFFICIENT_STOCK', 'PRODUCT_UNAVAILABLE'])

export function App() {
  const [view, setView] = useState<View>({ kind: 'shop' })
  // The cart lives here because both the product list (add) and the cart panel use it.
  // It is always replaced with the backend's response - never recalculated locally.
  const [cart, setCart] = useState<Cart | null>(null)
  const [cartError, setCartError] = useState<string | null>(null)
  // One checkout attempt = one idempotency key. Kept in a ref: it is not rendered, and it must
  // survive re-renders between a failed request and its retry.
  const checkoutAttemptKey = useRef<string | null>(null)
  // Changing the key remounts the product list, which reloads the catalog (stock numbers).
  const [catalogVersion, setCatalogVersion] = useState(0)

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

  async function refreshCart() {
    try {
      setCart(await api.getCart())
      setCartError(null)
    } catch (error) {
      setCartError(errorMessage(error))
    }
  }

  async function addToCart(productId: number) {
    setCart(await api.addToCart(productId, 1))
  }

  async function updateQuantity(productId: number, quantity: number) {
    setCart(await api.updateCartItem(productId, quantity))
  }

  async function removeItem(productId: number) {
    setCart(await api.removeCartItem(productId))
  }

  async function checkout(paymentScenario?: string) {
    checkoutAttemptKey.current ??= crypto.randomUUID()
    let order: Order
    try {
      order = await api.checkout(checkoutAttemptKey.current, paymentScenario)
    } catch (error) {
      if (!mayHaveBeenProcessed(error)) {
        // Rejected before anything happened (e.g. empty cart, stock): the next click is a new attempt.
        checkoutAttemptKey.current = null
      }
      if (error instanceof ApiError && STOCK_CHANGED_CODES.has(error.code)) {
        setCatalogVersion((version) => version + 1)
        await refreshCart()
      }
      throw error
    }
    checkoutAttemptKey.current = null
    setView({ kind: 'confirmation', order })
    // The backend emptied the cart, or put the items back if the payment failed.
    await refreshCart()
  }

  async function checkPaymentStatus(orderId: number) {
    const order = await api.reconcilePayment(orderId)
    setView({ kind: 'confirmation', order })
    if (order.status === 'PAYMENT_FAILED') {
      await refreshCart()
    }
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
        <OrderConfirmation
          order={view.order}
          onCheckPaymentStatus={checkPaymentStatus}
          onContinueShopping={() => setView({ kind: 'shop' })}
        />
      ) : (
        <main className="shop">
          <ProductList key={catalogVersion} onAddToCart={addToCart} />
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
