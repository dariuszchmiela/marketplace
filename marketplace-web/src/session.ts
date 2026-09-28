// Anonymous shopper identity for Phase 1 (no authentication yet).
// A random UUID is created once, kept in localStorage and sent as X-Session-Id.

const STORAGE_KEY = 'marketplace.sessionId'
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

// Used only when localStorage is unavailable (e.g. blocked storage): the session then
// lives as long as the page does.
let inMemorySessionId: string | null = null

export function getSessionId(): string {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored && UUID_PATTERN.test(stored)) {
      return stored
    }
    const created = crypto.randomUUID()
    localStorage.setItem(STORAGE_KEY, created)
    return created
  } catch {
    inMemorySessionId ??= crypto.randomUUID()
    return inMemorySessionId
  }
}
