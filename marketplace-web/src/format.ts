// Single currency for Phase 1. Formatting only - amounts are always calculated by the backend.
const priceFormat = new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'PLN' })

export function formatPrice(amount: number): string {
  return priceFormat.format(amount)
}
