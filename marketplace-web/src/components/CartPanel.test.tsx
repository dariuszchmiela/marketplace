import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { act } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { Cart } from '../api/types'
import { CartPanel } from './CartPanel'

const cart: Cart = {
  items: [
    {
      productId: 1,
      productExists: true,
      productName: 'Keyboard',
      unitPrice: 349.99,
      quantity: 2,
      lineTotal: 699.98,
      availableQuantity: 10,
    },
  ],
  total: 699.98,
}

function renderPanel(onCheckout: () => Promise<void>) {
  render(
    <CartPanel cart={cart} onUpdateQuantity={vi.fn()} onRemove={vi.fn()} onCheckout={onCheckout} />,
  )
  return screen.getByRole('button', { name: 'Checkout' }) as HTMLButtonElement
}

describe('CartPanel checkout', () => {
  afterEach(cleanup)

  it('disables the button while checkout runs and ignores repeated clicks', async () => {
    let finish: () => void = () => {}
    const onCheckout = vi.fn(() => new Promise<void>((resolve) => (finish = resolve)))
    const button = renderPanel(onCheckout)

    // Two clicks in the same tick: the second one arrives before React re-renders.
    act(() => {
      fireEvent.click(button)
      fireEvent.click(button)
    })

    expect(onCheckout).toHaveBeenCalledTimes(1)
    const pending = screen.getByRole('button', { name: 'Placing order…' }) as HTMLButtonElement
    expect(pending.disabled).toBe(true)

    await act(async () => finish())
  })

  it('shows the API error and allows another attempt after a failure', async () => {
    const onCheckout = vi.fn().mockRejectedValue(new ApiError(409, 'INSUFFICIENT_STOCK', 'Only 1 item(s) available'))
    const button = renderPanel(onCheckout)

    await act(async () => fireEvent.click(button))

    expect(screen.getByRole('alert').textContent).toContain('Only 1 item(s) available')
    expect((screen.getByRole('button', { name: 'Checkout' }) as HTMLButtonElement).disabled).toBe(false)
  })
})

describe('CartPanel quantity input', () => {
  afterEach(cleanup)

  function withQuantity(quantity: number): Cart {
    const [item] = cart.items
    return { items: [{ ...item, quantity, lineTotal: item.unitPrice! * quantity }], total: item.unitPrice! * quantity }
  }

  function panel(current: Cart) {
    return <CartPanel cart={current} onUpdateQuantity={vi.fn()} onRemove={vi.fn()} onCheckout={vi.fn()} />
  }

  function quantityInput() {
    return screen.getByRole('spinbutton', { name: 'Quantity of Keyboard' }) as HTMLInputElement
  }

  it('shows the quantity returned by the backend without remounting the row', () => {
    const { rerender } = render(panel(withQuantity(2)))
    const input = quantityInput()
    fireEvent.change(input, { target: { value: '7' } })

    rerender(panel(withQuantity(3)))

    // Same DOM node (stable key), but the draft follows the new backend quantity.
    expect(quantityInput()).toBe(input)
    expect(input.value).toBe('3')
  })

  it('keeps the typed draft when the backend quantity has not changed', () => {
    const { rerender } = render(panel(withQuantity(2)))
    fireEvent.change(quantityInput(), { target: { value: '5' } })

    rerender(panel(withQuantity(2)))

    expect(quantityInput().value).toBe('5')
    expect((screen.getByRole('button', { name: 'Update' }) as HTMLButtonElement).disabled).toBe(false)
  })
})
