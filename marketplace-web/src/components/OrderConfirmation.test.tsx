import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { act } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { Order } from '../api/types'
import { OrderConfirmation } from './OrderConfirmation'

const baseOrder: Order = {
  id: 7,
  status: 'PAID',
  paymentFailureReason: null,
  paymentId: 'pay-1',
  total: 349.99,
  createdAt: '2026-09-29T10:00:00Z',
  lines: [{ productId: 1, productName: 'Keyboard', unitPrice: 349.99, quantity: 1, lineTotal: 349.99 }],
}

function renderOrder(order: Partial<Order>, onCheckPaymentStatus = vi.fn().mockResolvedValue(undefined)) {
  render(
    <OrderConfirmation
      order={{ ...baseOrder, ...order }}
      onCheckPaymentStatus={onCheckPaymentStatus}
      onContinueShopping={vi.fn()}
    />,
  )
  return onCheckPaymentStatus
}

describe('OrderConfirmation', () => {
  afterEach(cleanup)

  it('renders a paid order', () => {
    renderOrder({ status: 'PAID' })

    expect(screen.getByRole('heading').textContent).toBe('Thank you! Your order has been paid.')
    expect(screen.queryByRole('button', { name: 'Check payment status' })).toBeNull()
  })

  it('renders a declined payment', () => {
    renderOrder({ status: 'PAYMENT_FAILED', paymentFailureReason: 'DECLINED' })

    expect(screen.getByRole('heading').textContent).toBe('Your payment was declined.')
    expect(screen.getByText(/Nothing was charged. The items are back in your cart/)).toBeTruthy()
  })

  it('renders a technical payment failure', () => {
    renderOrder({ status: 'PAYMENT_FAILED', paymentFailureReason: 'NOT_PROCESSED', paymentId: null })

    expect(screen.getByRole('heading').textContent).toBe('The payment could not be processed right now.')
    expect(screen.getByText(/technical problem and nothing was charged/)).toBeTruthy()
  })

  it('renders an unknown payment and lets the shopper check its status', async () => {
    const onCheck = renderOrder({ status: 'PAYMENT_UNKNOWN', paymentId: null })

    expect(screen.getByRole('heading').textContent).toBe('Payment status is being verified.')
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Check payment status' })))

    expect(onCheck).toHaveBeenCalledWith(7)
  })

  it('shows an error when the status check fails', async () => {
    renderOrder(
      { status: 'PAYMENT_UNKNOWN', paymentId: null },
      vi.fn().mockRejectedValue(new ApiError(503, 'PAYMENT_SERVICE_UNAVAILABLE', 'Payment service is not available')),
    )

    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Check payment status' })))

    expect(screen.getByRole('alert').textContent).toContain('Payment service is not available')
  })
})
