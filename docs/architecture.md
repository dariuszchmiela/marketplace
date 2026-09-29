# Architecture (Phase 2)

## Overview

```text
Browser (React, Vite dev server :5173)
   │  fetch /api/*  + X-Session-Id: <uuid from localStorage>
   │                + Idempotency-Key: <uuid per checkout attempt>   (checkout only)
   ▼
Vite proxy ──► marketplace-service (Java, Spring Boot, :8080) ──HTTP──► payment-service (Kotlin, Spring Boot, :8081)
                  │  Spring Data JPA / Hibernate                           in-memory payments (Phase 2 simplification)
                  ▼
               PostgreSQL (schema owned by Flyway)
```

Two backend services and one frontend. No messaging, no authentication yet.

## Backend: `marketplace-service`

Java 25, Spring Boot 4.1 (Spring Framework 7, Hibernate 7), Spring Web MVC, Spring Data JPA,
Bean Validation, Flyway, springdoc-openapi, Resilience4j 2.4 (core modules only). No Lombok.

### Packages (package-by-feature)

| Package | Responsibility |
|---|---|
| `product` | `Product` entity (with `@Version`), read-only catalog API, atomic stock return |
| `cart` | `Cart` aggregate (`Cart` + `CartItem`), cart API, early stock feedback |
| `checkout` | checkout orchestration, the two checkout transactions, payment reconciliation |
| `order` | `Order` (payment state machine) + immutable `OrderLine`, read API scoped to the session |
| `payment` | HTTP client for payment-service: timeouts, retry, circuit breaker, outcome classification |
| `session` | `SessionId` value + argument resolver reading `X-Session-Id` |
| `common` | `ErrorCode`, `MarketplaceException`, `ApiError`, `GlobalExceptionHandler` |

### API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/products` | ordered by id, no pagination (small seeded catalog) |
| GET | `/api/products/{id}` | 404 `PRODUCT_NOT_FOUND` |
| GET | `/api/cart` | returns an empty cart if the session has none (nothing persisted on read) |
| POST | `/api/cart/items` | `{productId, quantity}`; adds to an existing line if the product is already in the cart |
| PUT | `/api/cart/items/{productId}` | `{quantity}` sets the absolute quantity (≥ 1) |
| DELETE | `/api/cart/items/{productId}` | idempotent, removing a missing line is not an error |
| POST | `/api/checkout` | requires `Idempotency-Key: <uuid>`; 201 (created) or 200 (replay) + `Location` + order |
| POST | `/api/orders/{id}/reconcile-payment` | asks payment-service for the result of an open payment; returns the order |
| GET | `/api/orders` | orders of the current session, newest first |
| GET | `/api/orders/{id}` | 404 if the order belongs to another session |

Contract decisions:

- **Every cart mutation returns the whole recalculated cart.** The client never computes prices or totals.
- **Cart response is enriched with current product data** (name, current unit price, line total,
  available quantity). The cart itself stores only `productId` + `quantity`.
  If a product disappears from the catalog, its line has `productExists: false` and is excluded from the total.
- **Checkout has no request body.** It always checks out the session's current cart. The payment result
  is part of the response: the order's `status` (and `paymentFailureReason` / `paymentId`).
  A declined or failed payment is still `201` with the order: the order exists, its payment failed.
- **Money is `BigDecimal` / `NUMERIC(12,2)`, serialized as JSON numbers.** A single currency (PLN) is
  assumed; it is only named towards payment-service (`Order.CURRENCY`).
- **A cart line quantity is always 1–1000** (`CartItem.MAX_QUANTITY`), a domain invariant also reused by `@Max`.

### Error model

Every error, including Spring MVC's own, has the same body:

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

Business code throws `MarketplaceException(ErrorCode, message)`; the HTTP status is chosen in one
place (`GlobalExceptionHandler.statusFor`). Validation/header problems → 400 (incl. `MISSING_IDEMPOTENCY_KEY`,
`INVALID_IDEMPOTENCY_KEY`), not found → 404, stock conflicts and `PAYMENT_RECONCILIATION_CONFLICT` → 409,
empty cart → 422, `PAYMENT_SERVICE_UNAVAILABLE` (reconciliation could not reach payment-service) → 503,
anything unexpected → 500. payment-service uses the same body shape.

### Anonymous session

The browser generates a UUID once, stores it in `localStorage` and sends it as `X-Session-Id`.
`SessionIdArgumentResolver` converts it into a `SessionId` parameter. This is identification, not security.

## payment-service (Kotlin)

A small Spring Boot service in Kotlin 2.3 (Spring Web MVC, Bean Validation, Jackson Kotlin module). It plays
the external payment provider. Packages: `payments` (domain + business logic), `simulation` (failure scenarios),
`api` (controller, DTOs, error handler).

| Method | Path | Notes |
|---|---|---|
| POST | `/api/payments` | `{orderId, amount, currency, idempotencyKey}` → 201 new payment, 200 same key replayed, 409 key reused with other data |
| GET | `/api/payments/{paymentId}` | 404 `PAYMENT_NOT_FOUND` |
| GET | `/api/payments/by-idempotency-key/{key}` | used by marketplace reconciliation |

- **No card data** is ever sent: the marketplace only says what to charge for which order.
- **Statuses: `SUCCEEDED`, `DECLINED`.** A payment is recorded only together with its final result, so no
  intermediate states are needed. A decline is a 2xx business result, not an error.
- **Idempotency:** `InMemoryPaymentRepository.saveIfAbsent` uses `ConcurrentHashMap.computeIfAbsent`: for one key
  the payment is created at most once; concurrent requests with the same key wait and get the same payment.
  A replay with different business data (order, amount by value, currency) → 409 `IDEMPOTENCY_KEY_CONFLICT`.
- **In-memory storage is a Phase 2 simplification:** payments are lost on restart. A real provider would use a
  database with a unique constraint on the key (like marketplace checkout does).
- **Failure simulation** (`simulation/ScenarioSimulator`), selected per request with `X-Payment-Scenario`,
  deterministic, enabled by `payment.simulation.enabled` (default true: the service is a simulator). The
  controller calls three hooks around normal processing; `PaymentService` knows nothing about scenarios.

| Scenario | Behaviour |
|---|---|
| `SUCCESS` (or no header) | processes normally, `SUCCEEDED` |
| `DECLINED` | processes normally, `DECLINED` |
| `SLOW` | waits `slow-delay` (5 s) **before** processing, then records `SUCCEEDED` |
| `SERVER_ERROR` | 503 `PAYMENT_SERVICE_UNAVAILABLE` on every attempt, nothing recorded |
| `SERVER_ERROR_ONCE` | 503 on the first request for an idempotency key, then normal processing |
| `SUCCESS_BUT_SLOW_RESPONSE` | records `SUCCEEDED`, **then** waits `slow-delay` before responding |

**503 contract:** payment-service answers 503 only when it did not record anything, so the caller may treat
it as "not processed" and retry with the same key.

## Checkout with payment

### Transaction boundaries

`CheckoutService.checkout` is deliberately **not** `@Transactional`:

```text
1. OrderPlacementService.placeOrder          @Transactional (short)
   - checkout key already used by this session? → return that order (replay)
   - load cart + products, validate, snapshot prices
   - decrease stock, save Order(PAYMENT_PENDING, new payment idempotency key), clear cart
   COMMIT
2. PaymentClient.pay                          no transaction, no DB connection held
   - HTTP POST /api/payments with timeouts, retry, circuit breaker
3. OrderPaymentUpdater.applyOutcome           @Transactional (short), order row locked (SELECT … FOR UPDATE)
   - apply the outcome (see below)
   COMMIT
```

A slow payment-service therefore never holds a database transaction or a pooled connection.
`PaymentClient` also enforces it: calling it inside an active transaction throws `IllegalStateException`.
The integration test `orderIsCommittedBeforePaymentServiceIsCalled` proves that the order is committed
(visible from another connection) while payment-service is handling the request.

### Consistency model (Phase 2)

There is no distributed transaction and no saga framework. Each step is atomic on its own, and
the in-between states are explicit order statuses:

| Payment result | Order | Stock | Cart |
|---|---|---|---|
| succeeded | `PAID` | stays taken | stays empty |
| declined (business) | `PAYMENT_FAILED` / `DECLINED` | returned | items put back |
| provably not processed (503 after retries, connection refused, circuit open, 4xx) | `PAYMENT_FAILED` / `NOT_PROCESSED` | returned | items put back |
| unknown (read timeout, 500/502/504, 409, broken response) | `PAYMENT_UNKNOWN` | stays taken | stays empty |

- Stock is taken in transaction 1 so that a paid order always has its goods. It is returned with a single atomic
  `UPDATE … SET available_quantity = available_quantity + ?` (`ProductRepository.increaseStock`), which cannot lose
  a concurrent update and cannot fail with an optimistic-lock conflict, so a failed payment can always be closed.
- Items of a failed order are merged back into the session's cart (`Cart.restoreItem`, capped at the max quantity).
- An unknown result is **never** treated as a failure: the payment may have succeeded. Stock stays with the order
  until reconciliation decides. Nothing is silently lost in any branch.
- If the process dies between the steps, the order stays `PAYMENT_PENDING`; reconciliation also accepts it.

### Order state model

```text
PAYMENT_PENDING ──► PAID
       │  └──────► PAYMENT_FAILED  (DECLINED | NOT_PROCESSED)
       ▼                ▲
PAYMENT_UNKNOWN ────────┘   and ──► PAID
```

`PAID` and `PAYMENT_FAILED` are final. `NEW` remains only for Phase 1 orders (no transitions).
Transitions are defined in `OrderStatus.canTransitionTo` and only happen through `Order.markPaid`,
`markPaymentDeclined`, `markPaymentNotProcessed` and `markPaymentUnknown`; anything else throws.
`OrderPaymentUpdater` ignores an outcome for an order that is already final, so a late checkout thread and a
reconciliation cannot overwrite each other (the row lock serializes them).

### Checkout idempotency

- The client sends `Idempotency-Key: <uuid>`, one per checkout attempt, reused when retrying that attempt.
- Stored in PostgreSQL on the order (`orders.checkout_idempotency_key`), unique per session:
  `UNIQUE (session_id, checkout_idempotency_key)` (migration `V3__payment_integration.sql`).
- **Sequential replay:** transaction 1 finds the order by key and returns it (200) in its current state;
  payment-service is not called again, and the (possibly refilled) cart is not touched.
- **Concurrent duplicates:** both requests may miss each other's order at first. The loser then fails
  either on the unique constraint (its INSERT waits for the winner's commit), on the product version, or,
  if the winner committed between its key lookup and its cart read, with `CART_EMPTY`/`INSUFFICIENT_STOCK`.
  `CheckoutService` handles all of these the same way: if an order exists for the key, it is returned;
  otherwise the original failure is rethrown. (The `CART_EMPTY` race was found by the smoke test.)
- The order also gets its own **payment idempotency key** (`orders.payment_idempotency_key`, unique), generated
  once and used for every payment attempt and for reconciliation. Retries can never create a second payment.

### HTTP client, timeouts, retry, circuit breaker

`payment/PaymentClientConfiguration` builds everything from `payment.client.*` in `application.yaml`
(validated at startup; no defaults in code, no unlimited timeouts):

| Setting | Value | Meaning |
|---|---|---|
| `connect-timeout` | 1 s | TCP connect (JDK `HttpClient`) |
| `read-timeout` | 2 s | until response headers arrive (`JdkClientHttpRequestFactory`) |
| `retry.max-attempts` | 3 | total attempts incl. the first |
| `retry.initial-backoff` / `backoff-multiplier` / `randomization-factor` | 200 ms / 2.0 / 0.5 | exponential backoff with ±50 % jitter |
| `circuit-breaker.sliding-window-size` / `minimum-number-of-calls` | 10 / 5 | count-based window |
| `circuit-breaker.failure-rate-threshold` | 50 % | opens the circuit |
| `circuit-breaker.wait-duration-in-open-state` | 15 s | fail fast, no calls |
| `circuit-breaker.permitted-calls-in-half-open-state` | 2 | trial calls deciding close/reopen |

Client: Spring `RestClient` over the JDK `HttpClient` (HTTP/1.1). Resilience4j is used through its core API
(`Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, call))`), not annotations: the decoration
order and the failure classification are plain code, and the same factories build the client in tests.
Retry wraps the circuit breaker, so every attempt is recorded, and an open circuit is not retried.

Failure classification per attempt (`PaymentClient`):

| Failure | Retried | Proves "not processed" | Counts for circuit breaker |
|---|---|---|---|
| connection refused / connect timeout | yes | yes | yes |
| 503 (payment-service contract) | yes | yes | yes |
| 502, 504 | yes | no | yes |
| 500 / other 5xx | no | no | yes |
| read timeout / other I/O error | no | no | yes |
| 409 idempotency conflict | no | no | no |
| other 4xx | no | yes | no |
| circuit open | no | yes | – |

The outcome is `NotProcessed` only if **every** attempt proved that nothing was processed; one ambiguous
attempt (e.g. a 502 followed by 503s) makes it `Unknown`. A declined payment is a successful call with a
business result: never retried, never counted as a failure.

**Why a read timeout is not retried:** payment-service is already slow; a retry multiplies its load and the
shopper's waiting time (3 × 2 s), while the result can be recovered cheaply by reconciliation. Retrying it would
be *safe* (same idempotency key), just not useful in the synchronous request.

Logging is key=value style (`payment.request`, `payment.retry`, `payment.no_result`, `payment.circuit_breaker`,
`order.payment_status`, `reconciliation.*`, `checkout.*`) with order id, idempotency keys, attempt numbers and
outcomes. No personal or card data exists to log.

`payment.client.forward-scenario-header` (env `PAYMENT_FORWARD_SCENARIO_HEADER`, default **false**) forwards the
client's `X-Payment-Scenario` header to payment-service. Dev/test only.

### Unknown result and reconciliation

The key scenario: payment-service records `SUCCEEDED` but the response arrives after the marketplace's read
timeout. The marketplace cannot tell this apart from "not processed", so the order becomes `PAYMENT_UNKNOWN`.

`POST /api/orders/{id}/reconcile-payment` (`PaymentReconciliationService`), also offered in the UI as
"Check payment status":

1. load the order (session-scoped); if its status is final → return it unchanged (idempotent);
2. `GET /api/payments/by-idempotency-key/{paymentKey}` outside any transaction (same timeouts/retry/circuit breaker);
3. payment found → verify it belongs to the order (order id, amount, currency; mismatch → 409, order unchanged)
   and apply it: `PAID`, or `PAYMENT_FAILED` with stock and cart returned;
4. **no payment found → order unchanged** (`PAYMENT_UNKNOWN`), stock stays taken. "Not found" is not proof of
   failure: in the `SLOW` scenario the request is still being processed and records the payment seconds later
   (the smoke test shows exactly this). Reconciliation can simply be run again;
5. payment-service unreachable → 503 `PAYMENT_SERVICE_UNAVAILABLE`, order unchanged.

Reconciliation is explicit (endpoint + button), not scheduled: the simplest concrete, testable recovery path.

### Persistence

- Flyway migrations: `V1__create_schema.sql`, `V2__seed_products.sql`, `V3__payment_integration.sql`
  (payment columns, unique keys, `CHECK` constraints for status values, required keys and failure reason).
  Hibernate runs with `ddl-auto: validate`; `open-in-view` is disabled.
- No foreign keys from `cart_item`/`order_line` to `product` on purpose (cart is not the source of truth;
  order lines are snapshots).
- `Product.version` (`@Version`) protects stock decreases in checkout; conflicts are not yet translated (Phase 3).

## Frontend: `marketplace-web`

React 19 + TypeScript + Vite, plain CSS, no UI framework, no router, no global state library.

- `App.tsx` owns the cart and the current view. It keeps the **checkout attempt key** in a `useRef`: created on
  the first click of an attempt, reused when the request failed in a way that may have reached the backend
  (network error, 5xx), cleared after an order came back or the backend rejected the request (4xx).
  After checkout it reloads the cart (empty after success, restored after a failed payment).
- `CartPanel` disables the button and uses a `useRef` guard so a double click sends one request. In development
  builds it shows a "Payment scenario (dev)" select for the simulation header.
- `OrderConfirmation` renders the payment result: paid, declined, technical failure ("nothing was charged, items are
  back in your cart"), and pending/unknown ("Payment status is being verified.") with a "Check payment status" button
  that calls reconciliation.

## Tests

Backend `marketplace-service` (`mvn test`): unit tests (Mockito, domain), `PaymentClientTest` (real HTTP against
`FakePaymentServer`, a JDK `HttpServer` stand-in: timeouts, retry, same key, circuit breaker states, transaction guard),
integration tests with Testcontainers PostgreSQL + MockMvc + `FakePaymentServer` (`PaymentCheckoutIntegrationTest`:
paid, declined, retries, not processed, unknown → reconciliation, replay, concurrent duplicates, transaction boundary,
open circuit).

payment-service (`mvn test`): `PaymentServiceTest` (idempotency incl. 32 concurrent threads), `ScenarioSimulatorTest`,
`PaymentApiIntegrationTest` (real HTTP on a random port: every scenario, 16 concurrent HTTP requests with one key).

Frontend (`npm test`, Vitest + Testing Library): idempotency key reuse per attempt, one request per double click,
rendering of paid/declined/technical failure/unknown, reconciliation button.

## Not implemented yet (deliberately)

- **Automatic reconciliation / expiry:** no scheduler. An order whose payment is never found stays
  `PAYMENT_UNKNOWN` with its stock taken until someone reconciles it again. Resolving "never found" (e.g. resubmitting
  the payment with the same key, or voiding it after a grace period) needs a provider-side contract and is deferred.
- **Durable payment storage:** payment-service is in memory; a restart forgets payments (and reconciliation would then
  find nothing).
- **Events:** no Kafka, no outbox (Phase 4). The payment call is synchronous inside the checkout request.
- **Concurrency handling:** an `OptimisticLockingFailureException` from two shoppers buying the same product at the same
  moment still ends as a 500 (Phase 3). Duplicate checkouts of the same attempt are handled (see above).
- **Security:** no users, authentication or service-to-service auth; `X-Session-Id` is a bearer identifier only.
- **Operations:** no metrics, tracing, Actuator/health checks, containerized services or deployment.
- Catalog management, pagination, stock reservation with expiry, multiple currencies, taxes, shipping.
