// Mirrors the backend DTOs. Money arrives as JSON numbers; the frontend only
// displays amounts computed by the backend and never does arithmetic on them.

export interface Product {
  id: number
  name: string
  description: string
  price: number
  availableQuantity: number
}

export interface CartItem {
  productId: number
  /** false when the product was removed from the catalog; name/price/lineTotal are then null */
  productExists: boolean
  productName: string | null
  unitPrice: number | null
  quantity: number
  lineTotal: number | null
  availableQuantity: number
}

export interface Cart {
  items: CartItem[]
  total: number
}

export interface OrderLine {
  productId: number
  productName: string
  unitPrice: number
  quantity: number
  lineTotal: number
}

export type OrderStatus = 'NEW' | 'PAYMENT_PENDING' | 'PAID' | 'PAYMENT_FAILED' | 'PAYMENT_UNKNOWN'

/** Only set for PAYMENT_FAILED. In both cases nothing was charged. */
export type PaymentFailureReason = 'DECLINED' | 'NOT_PROCESSED'

export interface Order {
  id: number
  status: OrderStatus
  paymentFailureReason: PaymentFailureReason | null
  paymentId: string | null
  total: number
  createdAt: string
  lines: OrderLine[]
}

export interface ApiErrorBody {
  timestamp: string
  status: number
  code: string
  message: string
  path: string | null
  fieldErrors: { field: string; message: string }[]
}
