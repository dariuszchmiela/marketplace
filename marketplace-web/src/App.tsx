import { useEffect, useRef, useState } from 'react'
import { api, ApiError, errorMessage, isAbortError, onUnauthorized } from './api/client'
import type { Cart, Order, User } from './api/types'
import { AuthPanel } from './components/AuthPanel'
import { CartPanel } from './components/CartPanel'
import { ErrorMessage } from './components/ErrorMessage'
import { OrderConfirmation } from './components/OrderConfirmation'
import { ProductList } from './components/ProductList'

type View = { kind: 'shop' } | { kind: 'confirmation'; order: Order }

/** Who is using the app. 'loading' = asking the backend whether the session cookie is still valid. */
type Auth = { status: 'loading' } | { status: 'anonymous' } | { status: 'authenticated'; user: User }

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
  const [auth, setAuth] = useState<Auth>({ status: 'loading' })
  const [authNotice, setAuthNotice] = useState<string | null>(null)
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
  const userId = auth.status === 'authenticated' ? auth.user.id : null

  function becomeAnonymous(notice: string | null) {
    setAuth({ status: 'anonymous' })
    setAuthNotice(notice)
    setCart(null)
    setCartError(null)
    setView({ kind: 'shop' })
    checkoutAttemptKey.current = null
  }

  // Restore the session after a page reload: the browser still has the HttpOnly session cookie (if any);
  // /api/auth/me tells whether it is valid.
  useEffect(() => {
    const controller = new AbortController()
    api
      .me(controller.signal)
      .then((user) => setAuth({ status: 'authenticated', user }))
      .catch((error: unknown) => {
        if (!isAbortError(error)) {
          setAuth({ status: 'anonymous' })
        }
      })
    return () => controller.abort()
  }, [])

  // Any 401 later on (session expired or logged out in another tab) returns to the logged-out state.
  useEffect(() => {
    onUnauthorized(() => becomeAnonymous('Your session has ended. Please log in again.'))
    return () => onUnauthorized(null)
  }, [])

  // The cart belongs to the logged-in user: load it whenever the user changes.
  useEffect(() => {
    if (userId === null) {
      return
    }
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
  }, [userId])

  async function login(email: string, password: string) {
    const user = await api.login(email, password)
    setAuthNotice(null)
    setAuth({ status: 'authenticated', user })
  }

  async function register(email: string, password: string) {
    const user = await api.register(email, password)
    setAuthNotice(null)
    setAuth({ status: 'authenticated', user })
  }

  async function logout() {
    try {
      await api.logout()
    } finally {
      becomeAnonymous(null)
    }
  }

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
        {auth.status === 'authenticated' && (
          <span className="muted">
            {itemCount} item{itemCount === 1 ? '' : 's'} in cart · {auth.user.email}{' '}
            <button type="button" className="link-button" onClick={() => void logout()}>
              Log out
            </button>
          </span>
        )}
      </header>

      {view.kind === 'confirmation' && auth.status === 'authenticated' ? (
        <OrderConfirmation
          order={view.order}
          onCheckPaymentStatus={checkPaymentStatus}
          onContinueShopping={() => setView({ kind: 'shop' })}
        />
      ) : (
        <main className="shop">
          <ProductList key={catalogVersion} onAddToCart={addToCart} canAddToCart={auth.status === 'authenticated'} />
          <aside>
            {auth.status === 'loading' && <p className="muted">Loading…</p>}
            {auth.status === 'anonymous' && <AuthPanel onLogin={login} onRegister={register} notice={authNotice} />}
            {auth.status === 'authenticated' && (
              <>
                {cartError && <ErrorMessage message={cartError} />}
                {!cart && !cartError && <p className="muted">Loading cart…</p>}
                {cart && (
                  <CartPanel cart={cart} onUpdateQuantity={updateQuantity} onRemove={removeItem} onCheckout={checkout} />
                )}
              </>
            )}
          </aside>
        </main>
      )}
    </div>
  )
}
