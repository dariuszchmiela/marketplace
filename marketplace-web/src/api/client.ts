import type { ApiErrorBody, Cart, Order, Product, User } from './types'

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

// Authentication is the HttpOnly MARKETPLACE_SESSION cookie: the browser stores and sends it, this code never
// sees it. Nothing about the login is kept in localStorage.
//
// CSRF: the backend puts a token into the readable XSRF-TOKEN cookie (GET /api/auth/csrf); every state-changing
// request copies it into the X-XSRF-TOKEN header. A foreign site can make the browser send our cookies, but it
// cannot read them, so it cannot produce that header.
const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

export function readCsrfToken(): string | null {
  for (const cookie of document.cookie.split(';')) {
    const [name, ...value] = cookie.trim().split('=')
    if (name === CSRF_COOKIE && value.length > 0) {
      return decodeURIComponent(value.join('='))
    }
  }
  return null
}

/** Asks the backend for a (new) CSRF token cookie. Needed at start and after login/register/logout, which clear it. */
export async function refreshCsrfToken(): Promise<void> {
  await send('/api/auth/csrf', { method: 'GET' })
}

let unauthorizedHandler: (() => void) | null = null

/** Called when an API call answers 401 AUTHENTICATION_REQUIRED (e.g. the session expired). */
export function onUnauthorized(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

async function send(path: string, init: RequestInit): Promise<Response> {
  try {
    // 'include': cookies also work if the API is served from another (CORS-allowed) origin; same-origin via the
    // Vite proxy in development.
    return await fetch(path, { ...init, credentials: 'include' })
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the server. Is the backend running?')
  }
}

async function request<T>(path: string, init: RequestInit = {}, csrfRetried = false): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }
  if (!SAFE_METHODS.has(method)) {
    if (readCsrfToken() === null) {
      await refreshCsrfToken()
    }
    const token = readCsrfToken()
    if (token !== null) {
      headers.set(CSRF_HEADER, token)
    }
  }

  const response = await send(path, { ...init, method, headers })

  if (!response.ok) {
    const error = await toApiError(response)
    if (error.code === 'CSRF_FAILED' && !csrfRetried) {
      // Token missing/rotated: get a fresh one and repeat once. Safe - the request was rejected before anything
      // happened (and checkout carries its idempotency key anyway).
      await refreshCsrfToken()
      return request<T>(path, init, true)
    }
    if (error.status === 401 && error.code === 'AUTHENTICATION_REQUIRED') {
      unauthorizedHandler?.()
    }
    throw error
  }
  if (response.status === 204) {
    return undefined as T
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

  /** The logged-in user, or ApiError 401 AUTHENTICATION_REQUIRED. Used to restore the session after a reload. */
  me: (signal?: AbortSignal) => request<User>('/api/auth/me', { signal }),

  register: (email: string, password: string) =>
    request<User>('/api/auth/register', { method: 'POST', body: jsonBody({ email, password }) }),

  login: (email: string, password: string) =>
    request<User>('/api/auth/login', { method: 'POST', body: jsonBody({ email, password }) }),

  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),

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
