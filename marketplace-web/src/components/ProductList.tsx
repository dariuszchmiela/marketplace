import { useEffect, useRef, useState } from 'react'
import { api, errorMessage, isAbortError } from '../api/client'
import type { Product } from '../api/types'
import { formatPrice } from '../format'
import { ErrorMessage } from './ErrorMessage'

interface ProductListProps {
  onAddToCart: (productId: number) => Promise<void>
}

export function ProductList({ onAddToCart }: ProductListProps) {
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [addError, setAddError] = useState<string | null>(null)
  const [addingProductId, setAddingProductId] = useState<number | null>(null)
  // State updates are asynchronous; the ref blocks a second add synchronously,
  // even before React has re-rendered the buttons as disabled.
  const addInFlight = useRef(false)

  // Load products when the list is shown. The AbortController cancels the request if the
  // component unmounts first (and in React StrictMode's mount/unmount/mount in development).
  useEffect(() => {
    const controller = new AbortController()
    api
      .getProducts(controller.signal)
      .then((loaded) => {
        setProducts(loaded)
        setLoadError(null)
      })
      .catch((error: unknown) => {
        if (!isAbortError(error)) {
          setLoadError(errorMessage(error))
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false)
        }
      })
    return () => controller.abort()
  }, [])

  async function handleAdd(productId: number) {
    if (addInFlight.current) {
      return
    }
    addInFlight.current = true
    setAddingProductId(productId)
    setAddError(null)
    try {
      await onAddToCart(productId)
    } catch (error) {
      setAddError(errorMessage(error))
    } finally {
      addInFlight.current = false
      setAddingProductId(null)
    }
  }

  if (loading) {
    return <p className="muted">Loading products…</p>
  }
  if (loadError) {
    return <ErrorMessage message={loadError} />
  }

  return (
    <section>
      <h2>Products</h2>
      {addError && <ErrorMessage message={addError} onDismiss={() => setAddError(null)} />}
      {products.length === 0 && <p className="muted">No products available.</p>}
      <ul className="product-grid">
        {products.map((product) => (
          <li key={product.id} className="card">
            <h3>{product.name}</h3>
            <p className="muted">{product.description}</p>
            <p className="price">{formatPrice(product.price)}</p>
            <p className="muted">
              {product.availableQuantity > 0 ? `${product.availableQuantity} in stock` : 'Out of stock'}
            </p>
            <button
              type="button"
              onClick={() => handleAdd(product.id)}
              disabled={product.availableQuantity === 0 || addingProductId !== null}
            >
              {addingProductId === product.id ? 'Adding…' : 'Add to cart'}
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}
