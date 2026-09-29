import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { act } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import type { Product } from '../api/types'
import { ProductList } from './ProductList'

const products: Product[] = [
  { id: 1, name: 'Keyboard', description: 'Mechanical', price: 349.99, availableQuantity: 10 },
]

describe('ProductList add to cart', () => {
  beforeEach(() => {
    vi.spyOn(api, 'getProducts').mockResolvedValue(products)
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('sends one add request for two clicks in the same tick', async () => {
    let finish: () => void = () => {}
    const onAddToCart = vi.fn(() => new Promise<void>((resolve) => (finish = resolve)))
    render(<ProductList onAddToCart={onAddToCart} />)
    const button = (await screen.findByRole('button', { name: 'Add to cart' })) as HTMLButtonElement

    // Two clicks in the same tick: the second one arrives before React re-renders the button as disabled.
    act(() => {
      fireEvent.click(button)
      fireEvent.click(button)
    })

    expect(onAddToCart).toHaveBeenCalledTimes(1)
    expect(onAddToCart).toHaveBeenCalledWith(1)
    expect((screen.getByRole('button', { name: 'Adding…' }) as HTMLButtonElement).disabled).toBe(true)

    await act(async () => finish())

    // The guard is released once the request completes.
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Add to cart' })))
    expect(onAddToCart).toHaveBeenCalledTimes(2)
  })
})
