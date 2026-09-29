import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, errorMessage, onUnauthorized, readCsrfToken } from './client'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function setCsrfCookie(value: string | null) {
  document.cookie = value === null ? 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT' : `XSRF-TOKEN=${value}`
}

type Call = { url: string; init: RequestInit; headers: Headers }

function calls(fetchMock: ReturnType<typeof vi.fn>): Call[] {
  return fetchMock.mock.calls.map(([url, init]) => ({
    url: url as string,
    init: init as RequestInit,
    headers: new Headers((init as RequestInit).headers),
  }))
}

describe('api client', () => {
  beforeEach(() => setCsrfCookie('token-1'))

  afterEach(() => {
    vi.unstubAllGlobals()
    onUnauthorized(null)
    setCsrfCookie(null)
  })

  it('sends cookies (credentials) and never the legacy X-Session-Id header', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => jsonResponse(200, { items: [], total: 0 }))
    vi.stubGlobal('fetch', fetchMock)

    await api.getCart()
    await api.addToCart(1, 1)

    for (const call of calls(fetchMock)) {
      expect(call.init.credentials).toBe('include')
      expect(call.headers.has('X-Session-Id')).toBe(false)
    }
  })

  it('sends the CSRF token from the cookie on state-changing requests only', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => jsonResponse(200, { id: 1, items: [], total: 0 }))
    vi.stubGlobal('fetch', fetchMock)

    await api.getCart()
    await api.addToCart(1, 1)
    await api.checkout('key-1')
    await api.login('a@example.com', 'secret-password')

    const [get, add, checkout, login] = calls(fetchMock)
    expect(get.headers.get('X-XSRF-TOKEN')).toBeNull()
    expect(add.headers.get('X-XSRF-TOKEN')).toBe('token-1')
    expect(checkout.headers.get('X-XSRF-TOKEN')).toBe('token-1')
    expect(checkout.headers.get('Idempotency-Key')).toBe('key-1')
    expect(login.url).toBe('/api/auth/login')
    expect(login.headers.get('X-XSRF-TOKEN')).toBe('token-1')
  })

  it('sends the CSRF token on logout and accepts the empty 204', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(api.logout()).resolves.toBeUndefined()

    const [logout] = calls(fetchMock)
    expect(logout.url).toBe('/api/auth/logout')
    expect(logout.init.method).toBe('POST')
    expect(logout.headers.get('X-XSRF-TOKEN')).toBe('token-1')
  })

  it('fetches a CSRF token first when there is none (e.g. after login cleared it)', async () => {
    setCsrfCookie(null)
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url === '/api/auth/csrf') {
        setCsrfCookie('fresh-token')   // what the browser does with the Set-Cookie header
        return jsonResponse(200, { headerName: 'X-XSRF-TOKEN' })
      }
      return jsonResponse(200, { items: [], total: 0 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await api.addToCart(1, 1)

    const [csrf, add] = calls(fetchMock)
    expect(csrf.url).toBe('/api/auth/csrf')
    expect(add.headers.get('X-XSRF-TOKEN')).toBe('fresh-token')
    expect(readCsrfToken()).toBe('fresh-token')
  })

  it('refreshes a rejected CSRF token once and repeats the request', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(403, { status: 403, code: 'CSRF_FAILED', message: 'Missing or invalid CSRF token' }))
      .mockImplementationOnce(async () => {
        setCsrfCookie('rotated-token')
        return jsonResponse(200, { headerName: 'X-XSRF-TOKEN' })
      })
      .mockResolvedValueOnce(jsonResponse(200, { items: [], total: 0 }))
    vi.stubGlobal('fetch', fetchMock)

    await api.addToCart(1, 1)

    const [first, csrf, retry] = calls(fetchMock)
    expect(first.headers.get('X-XSRF-TOKEN')).toBe('token-1')
    expect(csrf.url).toBe('/api/auth/csrf')
    expect(retry.headers.get('X-XSRF-TOKEN')).toBe('rotated-token')
  })

  it('reports 401 AUTHENTICATION_REQUIRED to the unauthorized handler', async () => {
    const handler = vi.fn()
    onUnauthorized(handler)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(401, { status: 401, code: 'AUTHENTICATION_REQUIRED', message: 'Please log in' })),
    )

    const error = await api.getCart().catch((e: unknown) => e)

    expect((error as ApiError).status).toBe(401)
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('does not treat wrong credentials as an expired session', async () => {
    const handler = vi.fn()
    onUnauthorized(handler)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(401, { status: 401, code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' })),
    )

    const error = await api.login('a@example.com', 'wrong-password').catch((e: unknown) => e)

    expect(errorMessage(error)).toBe('Invalid email or password')
    expect(handler).not.toHaveBeenCalled()
  })

  it('turns the backend error body into an ApiError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(409, {
          status: 409,
          code: 'INSUFFICIENT_STOCK',
          message: 'Only 1 item(s) available',
          fieldErrors: [],
        }),
      ),
    )

    const error = await api.checkout(crypto.randomUUID()).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(409)
    expect((error as ApiError).code).toBe('INSUFFICIENT_STOCK')
    expect(errorMessage(error)).toBe('Only 1 item(s) available')
  })

  it('sends the checkout idempotency key and the optional payment scenario', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => jsonResponse(201, { id: 1 }))
    vi.stubGlobal('fetch', fetchMock)

    await api.checkout('key-1')
    await api.checkout('key-1', 'DECLINED')

    const [first, second] = calls(fetchMock)
    expect(first.headers.get('Idempotency-Key')).toBe('key-1')
    expect(first.headers.get('X-Payment-Scenario')).toBeNull()
    expect(second.headers.get('Idempotency-Key')).toBe('key-1')
    expect(second.headers.get('X-Payment-Scenario')).toBe('DECLINED')
  })

  it('reports a network failure with a readable message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const error = await api.getProducts().catch((e: unknown) => e)

    expect((error as ApiError).code).toBe('NETWORK_ERROR')
  })

  it('handles error responses that are not in the API error format', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad Gateway', { status: 502 })))

    const error = await api.getProducts().catch((e: unknown) => e)

    expect((error as ApiError).code).toBe('HTTP_ERROR')
    expect((error as ApiError).status).toBe(502)
  })
})
