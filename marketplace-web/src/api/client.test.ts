import { afterEach, describe, expect, it, vi } from 'vitest'
import { getSessionId } from '../session'
import { api, ApiError, errorMessage } from './client'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('api client', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends the anonymous session id with every request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { items: [], total: 0 }))
    vi.stubGlobal('fetch', fetchMock)

    await api.getCart()

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/cart')
    expect(new Headers(init.headers).get('X-Session-Id')).toBe(getSessionId())
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

    const error = await api.checkout().catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(409)
    expect((error as ApiError).code).toBe('INSUFFICIENT_STOCK')
    expect(errorMessage(error)).toBe('Only 1 item(s) available')
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
