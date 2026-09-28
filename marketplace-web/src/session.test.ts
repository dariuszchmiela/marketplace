import { beforeEach, describe, expect, it } from 'vitest'
import { getSessionId } from './session'

describe('getSessionId', () => {
  beforeEach(() => localStorage.clear())

  it('creates a UUID once and reuses it from localStorage', () => {
    const first = getSessionId()

    expect(first).toMatch(/^[0-9a-f-]{36}$/)
    expect(localStorage.getItem('marketplace.sessionId')).toBe(first)
    expect(getSessionId()).toBe(first)
  })

  it('replaces a corrupted stored value', () => {
    localStorage.setItem('marketplace.sessionId', 'not-a-uuid')

    const id = getSessionId()

    expect(id).not.toBe('not-a-uuid')
    expect(localStorage.getItem('marketplace.sessionId')).toBe(id)
  })
})
