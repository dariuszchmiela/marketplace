import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { act } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError } from './api/client'
import type { Cart, Order, User } from './api/types'
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

const alice: User = { id: 1, email: 'alice@example.com' }

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
    vi.spyOn(api, 'me').mockResolvedValue(alice)
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

  it('after a concurrent stock change shows the message, reloads cart and catalog, and retries as a new attempt', async () => {
    vi.spyOn(api, 'checkout')
      .mockRejectedValueOnce(
        new ApiError(409, 'CONCURRENT_STOCK_CHANGE', 'Another purchase changed the stock of a product in your cart at the same time.'),
      )
      .mockResolvedValueOnce(paidOrder)
    const button = await renderApp()
    const loadsBefore = { cart: vi.mocked(api.getCart).mock.calls.length, products: vi.mocked(api.getProducts).mock.calls.length }

    await act(async () => fireEvent.click(button))

    expect(screen.getByRole('alert').textContent).toContain('Another purchase changed the stock')
    expect(vi.mocked(api.getCart).mock.calls.length).toBe(loadsBefore.cart + 1)
    expect(vi.mocked(api.getProducts).mock.calls.length).toBe(loadsBefore.products + 1)

    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Checkout' })))
    const [first, retry] = checkoutKeys()
    // 409 is a definitive rejection (nothing was ordered), so the retry is a new attempt.
    expect(retry).not.toBe(first)
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

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function unauthenticated() {
  return new ApiError(401, 'AUTHENTICATION_REQUIRED', 'Please log in')
}

describe('App authentication', () => {
  beforeEach(() => {
    vi.spyOn(api, 'getProducts').mockResolvedValue([
      { id: 1, name: 'Keyboard', description: 'Mechanical', price: 349.99, availableQuantity: 10 },
    ])
    vi.spyOn(api, 'getCart').mockResolvedValue(cart)
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('shows the catalog but no cart or checkout while logged out', async () => {
    vi.spyOn(api, 'me').mockRejectedValue(unauthenticated())
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Log in to shop' })).toBeTruthy()
    const addButton = (await screen.findByRole('button', { name: 'Log in to buy' })) as HTMLButtonElement
    expect(addButton.disabled).toBe(true)
    expect(screen.queryByRole('button', { name: 'Checkout' })).toBeNull()
    expect(api.getCart).not.toHaveBeenCalled()
  })

  it('logs in, forgets the password and loads the cart of that user', async () => {
    vi.spyOn(api, 'me').mockRejectedValue(unauthenticated())
    const login = vi.spyOn(api, 'login').mockResolvedValue(alice)
    render(<App />)
    await screen.findByRole('heading', { name: 'Log in to shop' })

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'alice@example.com' } })
    const password = screen.getByLabelText('Password') as HTMLInputElement
    fireEvent.change(password, { target: { value: 'my secret password' } })
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Log in' })))

    expect(login).toHaveBeenCalledWith('alice@example.com', 'my secret password')
    expect(await screen.findByText(/alice@example.com/)).toBeTruthy()
    expect(await screen.findByRole('button', { name: 'Checkout' })).toBeTruthy()
    expect(api.getCart).toHaveBeenCalled()
  })

  it('clears the password field also after a failed login', async () => {
    vi.spyOn(api, 'me').mockRejectedValue(unauthenticated())
    vi.spyOn(api, 'login').mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'Invalid email or password'))
    render(<App />)
    await screen.findByRole('heading', { name: 'Log in to shop' })

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'alice@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong password' } })
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Log in' })))

    expect(screen.getByRole('alert').textContent).toContain('Invalid email or password')
    expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe('')
  })

  it('registers a new account and is logged in right away', async () => {
    vi.spyOn(api, 'me').mockRejectedValue(unauthenticated())
    const register = vi.spyOn(api, 'register').mockResolvedValue({ id: 2, email: 'bob@example.com' })
    render(<App />)
    await screen.findByRole('heading', { name: 'Log in to shop' })

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'bob@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'a long enough password' } })
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Create account' })))

    expect(register).toHaveBeenCalledWith('bob@example.com', 'a long enough password')
    expect(await screen.findByText(/bob@example.com/)).toBeTruthy()
  })

  it('restores the session after a reload through /api/auth/me', async () => {
    vi.spyOn(api, 'me').mockResolvedValue(alice)
    render(<App />)

    expect(await screen.findByText(/alice@example.com/)).toBeTruthy()
    expect(await screen.findByRole('button', { name: 'Checkout' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Log in to shop' })).toBeNull()
  })

  it('logs out and returns to the logged-out view', async () => {
    vi.spyOn(api, 'me').mockResolvedValue(alice)
    const logout = vi.spyOn(api, 'logout').mockResolvedValue(undefined)
    render(<App />)
    await screen.findByRole('button', { name: 'Checkout' })

    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Log out' })))

    expect(logout).toHaveBeenCalledTimes(1)
    expect(await screen.findByRole('heading', { name: 'Log in to shop' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Checkout' })).toBeNull()
  })

  it('a 401 from any API call (expired session) resets to the logged-out view', async () => {
    vi.restoreAllMocks()
    document.cookie = 'XSRF-TOKEN=token-1'
    // The real API client against a stubbed network: the session expires between loading the cart and checking out.
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init: RequestInit) => {
      if (url === '/api/auth/me') return jsonResponse(200, alice)
      if (url === '/api/products') return jsonResponse(200, [])
      if (url === '/api/cart') return jsonResponse(200, cart)
      if (url === '/api/checkout' && init.method === 'POST') {
        return jsonResponse(401, { status: 401, code: 'AUTHENTICATION_REQUIRED', message: 'Please log in' })
      }
      return jsonResponse(404, { status: 404, code: 'NOT_FOUND', message: 'Not found' })
    }))
    render(<App />)
    const checkout = await screen.findByRole('button', { name: 'Checkout' })

    await act(async () => fireEvent.click(checkout))

    expect(await screen.findByRole('heading', { name: 'Log in to shop' })).toBeTruthy()
    expect(screen.getByText('Your session has ended. Please log in again.')).toBeTruthy()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })
})
