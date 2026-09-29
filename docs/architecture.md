# Architecture (Phase 1)

## Overview

```text
Browser (React, Vite dev server :5173)
   │  fetch /api/*  + header X-Session-Id: <uuid from localStorage>
   ▼
Vite proxy ──► marketplace-service (Spring Boot, :8080)
                  │  Spring Data JPA / Hibernate
                  ▼
               PostgreSQL (schema owned by Flyway)
```

One backend service and one frontend. No messaging, no second service, no authentication yet.

## Backend: `marketplace-service`

Java 25, Spring Boot 4.1 (Spring Framework 7, Hibernate 7), Spring Web MVC, Spring Data JPA,
Bean Validation, Flyway, springdoc-openapi. No Lombok.

### Packages (package-by-feature)

| Package | Responsibility |
|---|---|
| `product` | `Product` entity (with `@Version`), read-only catalog API |
| `cart` | `Cart` aggregate (`Cart` + `CartItem`), cart API, early stock feedback |
| `checkout` | `CheckoutService`: turns a cart into an order in one transaction |
| `order` | `Order` + immutable `OrderLine`, read API scoped to the session |
| `session` | `SessionId` value + argument resolver reading `X-Session-Id` |
| `common` | `ErrorCode`, `MarketplaceException`, `ApiError`, `GlobalExceptionHandler` |

Layering inside a feature is plain: controller → service → repository/entity. Services are concrete
classes (no interface per service); there is nothing to swap them for yet.

### API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/products` | ordered by id, no pagination (small seeded catalog) |
| GET | `/api/products/{id}` | 404 `PRODUCT_NOT_FOUND` |
| GET | `/api/cart` | returns an empty cart if the session has none (nothing persisted on read) |
| POST | `/api/cart/items` | `{productId, quantity}`; adds to an existing line if the product is already in the cart |
| PUT | `/api/cart/items/{productId}` | `{quantity}` sets the absolute quantity (≥ 1) |
| DELETE | `/api/cart/items/{productId}` | idempotent, removing a missing line is not an error |
| POST | `/api/checkout` | 201 + `Location: /api/orders/{id}` + order body |
| GET | `/api/orders` | orders of the current session, newest first |
| GET | `/api/orders/{id}` | 404 if the order belongs to another session |

Contract decisions:

- **Every cart mutation returns the whole recalculated cart.** The client never computes prices or
  totals. It just renders the response.
- **Cart response is enriched with current product data** (name, current unit price, line total,
  available quantity). It is a preview. The cart itself stores only `productId` + `quantity`.
  If a product disappears from the catalog, its line has `productExists: false` and is excluded from the total.
- **Checkout has no request body.** It always checks out the session's current cart.
- **Money is `BigDecimal` / `NUMERIC(12,2)`, serialized as JSON numbers.** A single currency (PLN) is
  assumed and not modelled. The frontend only formats backend-computed amounts; it does no arithmetic.
- **A cart line quantity is always 1–1000** (`CartItem.MAX_QUANTITY`). This is a domain invariant, checked
  on creating a line, changing its quantity and adding to it, so repeated additions cannot exceed it either
  (the sum is computed as `long`, no int overflow). Exceeding it returns `400 INVALID_QUANTITY`. The request
  DTOs reuse the same constant in `@Max` for early Bean Validation feedback (`400 VALIDATION_FAILED`).

### Error model

Every error, including Spring MVC's own (malformed JSON, unknown path, wrong method, type mismatch),
has the same body:

```json
{
  "timestamp": "2026-09-28T21:03:09.258Z",
  "status": 409,
  "code": "INSUFFICIENT_STOCK",
  "message": "Only 3 item(s) of 'Laptop Stand' available, requested 50",
  "path": "/api/cart/items",
  "fieldErrors": []
}
```

Business code throws `MarketplaceException(ErrorCode, message)`. The HTTP status is chosen in one
place (`GlobalExceptionHandler.statusFor`), so domain/service code does not know about HTTP.
Main mappings: validation/malformed input and session header problems → 400, not found → 404,
stock/availability conflicts → 409, empty cart → 422, anything unexpected → 500 (logged, generic message).

### Anonymous session

The browser generates a UUID once, stores it in `localStorage` and sends it as `X-Session-Id`.
`SessionIdArgumentResolver` converts the header into a `SessionId` controller parameter (400 if missing
or not a UUID). Controllers and services only see `SessionId`, so moving to real authentication later
means changing the resolver, not the endpoints. This is identification, not security: anyone who knows
a session UUID can use that cart/orders.

### Checkout

`CheckoutService.checkout` runs in a single `@Transactional` method:

1. load the session's cart; missing or empty → `CART_EMPTY`;
2. load all products in the cart with one query (`findAllById`) into a `Map<id, Product>`;
3. map every cart item to an `OrderLine` (stream, no side effects): product must exist
   (`PRODUCT_UNAVAILABLE`) and have enough stock (`INSUFFICIENT_STOCK`); the **current** name and price
   are copied into the line;
4. only after all lines are valid, decrease stock (plain loop, side effects outside the stream);
5. create the `Order` (status `NEW`, total = sum of line totals) and save it;
6. clear the cart;
7. return the order DTO.

Any exception rolls the whole transaction back: no stock change, no order, cart unchanged
(covered by `CheckoutFlowIntegrationTest.failedCheckoutRollsBackAndLeavesStockOrdersAndCartUntouched`).

Adding to / updating the cart also checks stock, but only as early feedback. It is **not** a reservation,
and checkout re-validates everything.

### Persistence

- Schema is created by Flyway (`db/migration/V1__create_schema.sql`); `V2__seed_products.sql` seeds products.
  Hibernate runs with `ddl-auto: validate`.
- `open-in-view` is disabled: entities never leave the transactional service layer, which maps them to DTOs.
- `cart_item.product_id` and `order_line.product_id` have **no foreign key** to `product` on purpose:
  the cart is not the source of truth for products, and order lines are historical snapshots.
- `Product.version` is a JPA `@Version` column. Because checkout updates products, Hibernate already
  uses it (`UPDATE … WHERE version = ?`). Nothing handles the resulting conflict yet (see below).
- `CartRepository.findBySessionId` and the order queries use `@EntityGraph` to fetch items/lines
  together with their parent (no N+1 when mapping to DTOs).

### Order model

`OrderStatus` has a single value, `NEW`. Payment states are added only once payment exists.
`OrderLine` has no setters and all its columns are `updatable = false`; it stores
`productName`, `unitPrice`, `quantity` and `lineTotal` as of checkout time.

## Frontend: `marketplace-web`

React 19 + TypeScript + Vite, plain CSS, no UI framework, no router, no global state library.

- `App.tsx` owns the cart state (shared by the product list and the cart panel) and the current view
  (`shop` or `confirmation`). The cart is always replaced with the backend's response.
- `ProductList` loads products in a `useEffect` with an `AbortController` (cancelled on unmount and in
  StrictMode's dev double-mount). It remounts after checkout, so stock numbers are refreshed.
- `CartPanel` handles quantity updates, removal and checkout. The checkout button is disabled while a
  request runs, and a `useRef` flag blocks a second click that arrives before React re-renders.
- `api/client.ts` adds `X-Session-Id` to every request and turns every failure (backend `ApiError`,
  non-JSON error, network error) into a typed `ApiError` with a readable message.

## Tests

Backend (`mvn test`, 38 tests):

- unit tests with Mockito: `CartServiceTest`, `CheckoutServiceTest`, `OrderTest`;
- integration tests with Testcontainers PostgreSQL + MockMvc over the full application:
  `CheckoutFlowIntegrationTest` (full flow, price snapshot, rollback, session scoping) and
  `ApiErrorIntegrationTest` (error contract).

Frontend (`npm test`, Vitest + Testing Library): session id persistence, API client error handling,
duplicate-checkout prevention.

## Not implemented yet (deliberately)

These belong to later phases and do **not** exist in the code:

- **Payment**: no `payment-service`, no payment states, no HTTP client, timeouts, retries or circuit breaker.
- **Idempotency**: no idempotency key on checkout. The UI prevents double clicks and a sequential repeat
  finds an empty cart, but two truly concurrent checkout requests for the same cart are not handled.
- **Concurrency handling**: `@Version` is present, but an `OptimisticLockingFailureException`
  (two shoppers buying the same product at the same moment) is not translated. It currently ends as
  a generic 500 `INTERNAL_ERROR`. No pessimistic locking, no retry. Two first-time "add to cart"
  requests racing for the same new session can likewise hit the unique constraint on `cart.session_id`.
- **Events**: no Kafka, no outbox, no `OrderCreated` events.
- **Security**: no users, authentication or authorization; `X-Session-Id` is a bearer identifier only.
  No CORS configuration (not needed: the dev proxy keeps everything same-origin), no CSRF.
- **Operations**: no metrics, tracing, Actuator, containerized backend/frontend or deployment.
- **Catalog management**: no endpoints to create/update/delete products; no pagination or search.
- **Stock reservation**: stock is decreased only at checkout, and the cart does not reserve it.
- Abandoned cart cleanup, multiple currencies, taxes, shipping.
