import { useRef, useState } from 'react'
import { errorMessage } from '../api/client'
import type { Order } from '../api/types'
import { formatPrice } from '../format'
import { ErrorMessage } from './ErrorMessage'

interface OrderConfirmationProps {
  order: Order
  onCheckPaymentStatus: (orderId: number) => Promise<void>
  onContinueShopping: () => void
}

interface StatusMessage {
  title: string
  details: string
  tone: 'success' | 'failure' | 'pending'
}

function statusMessage(order: Order): StatusMessage {
  switch (order.status) {
    case 'PAID':
      return { title: 'Thank you! Your order has been paid.', details: '', tone: 'success' }
    case 'PAYMENT_FAILED':
      return order.paymentFailureReason === 'DECLINED'
        ? {
            title: 'Your payment was declined.',
            details: 'Nothing was charged. The items are back in your cart, so you can try again.',
            tone: 'failure',
          }
        : {
            title: 'The payment could not be processed right now.',
            details: 'This was a technical problem and nothing was charged. The items are back in your cart; please try again later.',
            tone: 'failure',
          }
    case 'PAYMENT_PENDING':
    case 'PAYMENT_UNKNOWN':
      return {
        title: 'Payment status is being verified.',
        details: 'We did not get a final answer from the payment provider yet. Your items are reserved for this order.',
        tone: 'pending',
      }
    case 'NEW':
      return { title: 'Your order has been placed.', details: '', tone: 'success' }
  }
}

export function OrderConfirmation({ order, onCheckPaymentStatus, onContinueShopping }: OrderConfirmationProps) {
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const checkInFlight = useRef(false)
  const message = statusMessage(order)
  const awaitingResult = order.status === 'PAYMENT_PENDING' || order.status === 'PAYMENT_UNKNOWN'

  async function handleCheck() {
    if (checkInFlight.current) {
      return
    }
    checkInFlight.current = true
    setChecking(true)
    setError(null)
    try {
      await onCheckPaymentStatus(order.id)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      checkInFlight.current = false
      setChecking(false)
    }
  }

  return (
    <section className="card confirmation">
      <h2 className={`status-${message.tone}`}>{message.title}</h2>
      {message.details && <p>{message.details}</p>}
      <p>
        Order number: <strong>#{order.id}</strong> · Status: <strong>{order.status}</strong> · Placed:{' '}
        {new Date(order.createdAt).toLocaleString()}
      </p>
      {error && <ErrorMessage message={error} onDismiss={() => setError(null)} />}
      {awaitingResult && (
        <button type="button" onClick={handleCheck} disabled={checking}>
          {checking ? 'Checking…' : 'Check payment status'}
        </button>
      )}
      <table>
        <thead>
          <tr>
            <th>Product</th>
            <th className="number">Unit price</th>
            <th className="number">Quantity</th>
            <th className="number">Line total</th>
          </tr>
        </thead>
        <tbody>
          {order.lines.map((line) => (
            <tr key={line.productId}>
              <td>{line.productName}</td>
              <td className="number">{formatPrice(line.unitPrice)}</td>
              <td className="number">{line.quantity}</td>
              <td className="number">{formatPrice(line.lineTotal)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td colSpan={3}>Total</td>
            <td className="number">
              <strong>{formatPrice(order.total)}</strong>
            </td>
          </tr>
        </tfoot>
      </table>
      <button type="button" className="primary" onClick={onContinueShopping}>
        Continue shopping
      </button>
    </section>
  )
}
