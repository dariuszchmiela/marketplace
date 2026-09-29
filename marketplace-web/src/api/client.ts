import { getSessionId } from '../session'
import type { ApiErrorBody, Cart, Order, Product } from './types'

/** Any failed API call. `code` is the backend error code, or a client-side one. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: ApiErrorBody['fieldErrors']

  constructor(status: number, code: string, message: string, fieldErrors: ApiErrorBody['fieldErrors'] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const details = error.fieldErrors.map((fieldError) => `${fieldError.field}: ${fieldError.message}`)
    return details.length > 0 ? `${error.message} (${details.join(', ')})` : error.message
  }
  return 'Something went wrong. Please try again.'
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  headers.set('X-Session-Id', getSessionId())
  if (init.body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the server. Is the backend running?')
  }

  if (!response.ok) {
    throw await toApiError(response)
  }
  return (await response.json()) as T
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as Partial<ApiErrorBody>
    if (body.code && body.message) {
      return new ApiError(response.status, body.code, body.message, body.fieldErrors ?? [])
    }
  } catch {
    // not our error format (e.g. proxy error page) - fall through
  }
  return new ApiError(response.status, 'HTTP_ERROR', `Request failed with status ${response.status}`)
}

function jsonBody(value: unknown): string {
  return JSON.stringify(value)
}

export const api = {
  getProducts: (signal?: AbortSignal) => request<Product[]>('/api/products', { signal }),

  getCart: (signal?: AbortSignal) => request<Cart>('/api/cart', { signal }),

  addToCart: (productId: number, quantity: number) =>
    request<Cart>('/api/cart/items', { method: 'POST', body: jsonBody({ productId, quantity }) }),

  updateCartItem: (productId: number, quantity: number) =>
    request<Cart>(`/api/cart/items/${productId}`, { method: 'PUT', body: jsonBody({ quantity }) }),

  removeCartItem: (productId: number) => request<Cart>(`/api/cart/items/${productId}`, { method: 'DELETE' }),

  // The same idempotency key must be sent again when retrying the same checkout attempt.
  // paymentScenario is a dev-only failure simulation header (ignored unless the backend enables it).
  checkout: (idempotencyKey: string, paymentScenario?: string) =>
    request<Order>('/api/checkout', {
      method: 'POST',
      headers: {
        'Idempotency-Key': idempotencyKey,
        ...(paymentScenario ? { 'X-Payment-Scenario': paymentScenario } : {}),
      },
    }),

  reconcilePayment: (orderId: number) => request<Order>(`/api/orders/${orderId}/reconcile-payment`, { method: 'POST' }),
}
