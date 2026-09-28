import type { Order } from '../api/types'
import { formatPrice } from '../format'

interface OrderConfirmationProps {
  order: Order
  onContinueShopping: () => void
}

export function OrderConfirmation({ order, onContinueShopping }: OrderConfirmationProps) {
  return (
    <section className="card confirmation">
      <h2>Thank you! Your order has been placed.</h2>
      <p>
        Order number: <strong>#{order.id}</strong> · Status: <strong>{order.status}</strong> · Placed:{' '}
        {new Date(order.createdAt).toLocaleString()}
      </p>
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
