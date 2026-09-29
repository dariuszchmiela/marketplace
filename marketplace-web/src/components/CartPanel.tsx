import { useRef, useState } from 'react'
import { errorMessage } from '../api/client'
import type { Cart, CartItem } from '../api/types'
import { formatPrice } from '../format'
import { ErrorMessage } from './ErrorMessage'

interface CartPanelProps {
  cart: Cart
  onUpdateQuantity: (productId: number, quantity: number) => Promise<void>
  onRemove: (productId: number) => Promise<void>
  /** paymentScenario: dev-only payment-service failure simulation, undefined for normal checkout */
  onCheckout: (paymentScenario?: string) => Promise<void>
}

// Only honoured when marketplace-service runs with PAYMENT_FORWARD_SCENARIO_HEADER=true.
const PAYMENT_SCENARIOS = [
  'SUCCESS',
  'DECLINED',
  'SLOW',
  'SERVER_ERROR',
  'SERVER_ERROR_ONCE',
  'SUCCESS_BUT_SLOW_RESPONSE',
] as const

export function CartPanel({ cart, onUpdateQuantity, onRemove, onCheckout }: CartPanelProps) {
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [checkingOut, setCheckingOut] = useState(false)
  // State updates are asynchronous; the ref blocks a second checkout synchronously,
  // even before React has re-rendered the button as disabled.
  const checkoutInFlight = useRef(false)
  const [paymentScenario, setPaymentScenario] = useState<string>('SUCCESS')

  async function runItemAction(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function handleCheckout() {
    if (checkoutInFlight.current) {
      return
    }
    checkoutInFlight.current = true
    setCheckingOut(true)
    setError(null)
    try {
      await onCheckout(paymentScenario === 'SUCCESS' ? undefined : paymentScenario)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      checkoutInFlight.current = false
      setCheckingOut(false)
    }
  }

  const isEmpty = cart.items.length === 0

  return (
    <section className="card cart">
      <h2>Cart</h2>
      {error && <ErrorMessage message={error} onDismiss={() => setError(null)} />}
      {isEmpty ? (
        <p className="muted">Your cart is empty.</p>
      ) : (
        <ul className="cart-items">
          {cart.items.map((item) => (
            <CartRow
              key={item.productId}
              item={item}
              disabled={busy || checkingOut}
              onUpdate={(quantity) => runItemAction(() => onUpdateQuantity(item.productId, quantity))}
              onRemove={() => runItemAction(() => onRemove(item.productId))}
            />
          ))}
        </ul>
      )}
      <p className="total">
        Total: <strong>{formatPrice(cart.total)}</strong>
      </p>
      {import.meta.env.DEV && (
        <label className="dev-scenario muted">
          Payment scenario (dev):{' '}
          <select value={paymentScenario} onChange={(event) => setPaymentScenario(event.target.value)}>
            {PAYMENT_SCENARIOS.map((scenario) => (
              <option key={scenario} value={scenario}>
                {scenario}
              </option>
            ))}
          </select>
        </label>
      )}
      <button type="button" className="primary" onClick={handleCheckout} disabled={isEmpty || busy || checkingOut}>
        {checkingOut ? 'Placing order…' : 'Checkout'}
      </button>
    </section>
  )
}

interface CartRowProps {
  item: CartItem
  disabled: boolean
  onUpdate: (quantity: number) => void
  onRemove: () => void
}

function CartRow({ item, disabled, onUpdate, onRemove }: CartRowProps) {
  // Local draft of the input. Whenever the server returns a different quantity, the draft is
  // reset to it. This adjusts state during render (React's recommended pattern for deriving
  // state from a changed prop), so no stale value is ever painted.
  const [quantityInput, setQuantityInput] = useState(String(item.quantity))
  const [syncedQuantity, setSyncedQuantity] = useState(item.quantity)
  if (item.quantity !== syncedQuantity) {
    setSyncedQuantity(item.quantity)
    setQuantityInput(String(item.quantity))
  }

  const parsed = Number(quantityInput)
  const isValid = Number.isInteger(parsed) && parsed >= 1
  const changed = isValid && parsed !== item.quantity

  return (
    <li className="cart-item">
      <div>
        <strong>{item.productName ?? `Product #${item.productId}`}</strong>
        {item.productExists && item.unitPrice !== null ? (
          <div className="muted">{formatPrice(item.unitPrice)} each</div>
        ) : (
          <div className="warning">No longer available</div>
        )}
        {item.productExists && item.quantity > item.availableQuantity && (
          <div className="warning">Only {item.availableQuantity} in stock</div>
        )}
      </div>
      <form
        className="quantity-form"
        onSubmit={(event) => {
          event.preventDefault()
          if (changed) {
            onUpdate(parsed)
          }
        }}
      >
        <input
          type="number"
          min={1}
          step={1}
          aria-label={`Quantity of ${item.productName ?? item.productId}`}
          value={quantityInput}
          onChange={(event) => setQuantityInput(event.target.value)}
          disabled={disabled}
        />
        <button type="submit" disabled={disabled || !changed}>
          Update
        </button>
        <button type="button" className="link-button" onClick={onRemove} disabled={disabled}>
          Remove
        </button>
      </form>
      <div className="line-total">{item.lineTotal !== null ? formatPrice(item.lineTotal) : '—'}</div>
    </li>
  )
}
