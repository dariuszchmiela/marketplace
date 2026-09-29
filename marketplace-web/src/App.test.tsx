import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { act } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './api/client'
import type { Cart, Order } from './api/types'
import { App } from './App'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const cart: Cart = {
  items: [
    {
      productId: 1,
      productExists: true,
      productName: 'Keyboard',
      unitPrice: 349.99,
      quantity: 1,
      lineTotal: 349.99,
      availableQuantity: 10,
    },
  ],
  total: 349.99,
}

const paidOrder: Order = {
  id: 7,
  status: 'PAID',
  paymentFailureReason: null,
  paymentId: 'pay-1',
  total: 349.99,
  createdAt: '2026-09-29T10:00:00Z',
  lines: [{ productId: 1, productName: 'Keyboard', unitPrice: 349.99, quantity: 1, lineTotal: 349.99 }],
}

async function renderApp() {
  render(<App />)
  return screen.findByRole('button', { name: 'Checkout' })
}

function checkoutKeys() {
  return vi.mocked(api.checkout).mock.calls.map(([idempotencyKey]) => idempotencyKey)
}

describe('App checkout', () => {
  beforeEach(() => {
    vi.spyOn(api, 'getProducts').mockResolvedValue([])
    vi.spyOn(api, 'getCart').mockResolvedValue(cart)
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('reuses the idempotency key when the same attempt is retried after a technical failure', async () => {
    vi.spyOn(api, 'checkout')
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the server. Is the backend running?'))
      .mockResolvedValueOnce(paidOrder)
      .mockResolvedValueOnce(paidOrder)
    const button = await renderApp()

    await act(async () => fireEvent.click(button))
    expect(screen.getByRole('alert').textContent).toContain('Cannot reach the server')

    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Checkout' })))
    expect(await screen.findByText('Thank you! Your order has been paid.')).toBeTruthy()

    // A completed attempt is over: the next checkout is a new attempt with a new key.
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Continue shopping' })))
    await act(async () => fireEvent.click(await screen.findByRole('button', { name: 'Checkout' })))

    const [first, retry, next] = checkoutKeys()
    expect(first).toMatch(UUID_PATTERN)
    expect(retry).toBe(first)
    expect(next).toMatch(UUID_PATTERN)
    expect(next).not.toBe(first)
  })

  it('starts a new attempt after a rejected checkout', async () => {
    vi.spyOn(api, 'checkout')
      .mockRejectedValueOnce(new ApiError(409, 'INSUFFICIENT_STOCK', 'Only 0 item(s) available'))
      .mockResolvedValueOnce(paidOrder)
    const button = await renderApp()

    await act(async () => fireEvent.click(button))
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Checkout' })))

    const [first, second] = checkoutKeys()
    expect(second).not.toBe(first)
  })

  it('sends one checkout request for a double click', async () => {
    let finish: () => void = () => {}
    const checkout = vi
      .spyOn(api, 'checkout')
      .mockImplementation(() => new Promise<Order>((resolve) => (finish = () => resolve(paidOrder))))
    const button = await renderApp()

    // Both clicks arrive before React re-renders the button as disabled.
    act(() => {
      fireEvent.click(button)
      fireEvent.click(button)
    })

    expect(checkout).toHaveBeenCalledTimes(1)
    await act(async () => finish())
    expect(await screen.findByText('Thank you! Your order has been paid.')).toBeTruthy()
  })

  it('refreshes the cart after a failed payment, because the backend put the items back', async () => {
    vi.spyOn(api, 'checkout').mockResolvedValue({
      ...paidOrder,
      status: 'PAYMENT_FAILED',
      paymentFailureReason: 'DECLINED',
    })
    const button = await renderApp()
    const getCart = vi.mocked(api.getCart)
    const loadsBefore = getCart.mock.calls.length

    await act(async () => fireEvent.click(button))

    expect(await screen.findByText('Your payment was declined.')).toBeTruthy()
    expect(getCart.mock.calls.length).toBe(loadsBefore + 1)
  })
})
