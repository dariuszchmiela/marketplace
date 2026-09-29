# Architecture (Phase 5)

## Overview

```text
Browser (React, Vite dev server :5173)
   │  fetch /api/*  + cookies MARKETPLACE_SESSION (HttpOnly) and XSRF-TOKEN
   │                + X-XSRF-TOKEN header on POST/PUT/DELETE, Idempotency-Key on checkout
   ▼
Vite proxy ──► marketplace-service (Java, Spring Boot, :8080) ──HTTP + Bearer service token──► payment-service (Kotlin, :8081)
                  │  Spring Data JPA / Hibernate                           in-memory payments (Phase 2 simplification)
                  ▼
               PostgreSQL public schema (app_user, spring_session, orders, …, outbox_event)
                  │  outbox publisher (polling)
                  ▼
               Kafka  marketplace.order-events (3 partitions, key = orderId)
                  │
                  ▼
               order-activity-service (Java, :8082) ──► PostgreSQL schema order_activity
                  └─ failures after retries ──► Kafka marketplace.order-events.DLT
```

Three backend services and one frontend. The frontend uses only the synchronous marketplace API; the event path is
downstream of it and never on a user request path. Users authenticate with a server-side session (Phase 5).

## Backend: `marketplace-service`

Java 25, Spring Boot 4.1 (Spring Framework 7, Hibernate 7), Spring Web MVC, Spring Data JPA,
Bean Validation, Flyway, springdoc-openapi, Resilience4j 2.4 (core modules only), Spring Security 7, Spring Session JDBC.
No Lombok.

### Packages (package-by-feature)

| Package | Responsibility |
|---|---|
| `product` | `Product` entity (with `@Version`), read-only catalog API, atomic stock return |
| `cart` | `Cart` aggregate (`Cart` + `CartItem`), cart API, early stock feedback |
| `checkout` | checkout orchestration, the two checkout transactions, payment reconciliation |
| `order` | `Order` (payment state machine) + immutable `OrderLine`, read API scoped to the session; `order.events`: order integration events written to the outbox |
| `payment` | HTTP client for payment-service: timeouts, retry, circuit breaker, outcome classification |
| `outbox` | transactional outbox: `OutboxWriter` (same transaction), `OutboxRepository` (JDBC, SKIP LOCKED claim), `OutboxPublisher` (polling), `KafkaEventSender` |
| `auth` | users (`app_user`), registration, JSON login/logout/me, Spring Security configuration (session, CSRF, CORS, JSON 401/403) |
| `session` | `SessionId` (owner key of carts and orders) + argument resolver taking it from the authenticated principal |
| `common` | `ErrorCode`, `MarketplaceException`, `ApiError`, `GlobalExceptionHandler` |

### API

Everything except the catalog, `/api/auth/csrf`, register and login requires a login (session cookie); all POST/PUT/DELETE
require the CSRF header.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/products` | ordered by id, no pagination (small seeded catalog) |
| GET | `/api/products/{id}` | 404 `PRODUCT_NOT_FOUND` |
| GET | `/api/auth/csrf` | public; sets the readable `XSRF-TOKEN` cookie |
| POST | `/api/auth/register` | public + CSRF; `{email, password}` → 201 `{id, email}`, logged in; 409 `EMAIL_ALREADY_REGISTERED` |
| POST | `/api/auth/login` | public + CSRF; → 200 `{id, email}`, new session id; 401 `INVALID_CREDENTIALS` |
| POST | `/api/auth/logout` | login + CSRF; 204, session deleted |
| GET | `/api/auth/me` | login; `{id, email}` (restores the UI after a reload) |
| GET | `/api/cart` | returns an empty cart if the user has none (nothing persisted on read) |
| POST | `/api/cart/items` | `{productId, quantity}`; adds to an existing line if the product is already in the cart |
| PUT | `/api/cart/items/{productId}` | `{quantity}` sets the absolute quantity (≥ 1) |
| DELETE | `/api/cart/items/{productId}` | idempotent, removing a missing line is not an error |
| POST | `/api/checkout` | requires `Idempotency-Key: <uuid>`; 201 (created) or 200 (replay) + `Location` + order |
| POST | `/api/orders/{id}/reconcile-payment` | asks payment-service for the result of an open payment; returns the order |
| GET | `/api/orders` | orders of the logged-in user, newest first |
| GET | `/api/orders/{id}` | 404 if the order belongs to another user |

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
`INVALID_IDEMPOTENCY_KEY`), not found → 404, stock conflicts, concurrency conflicts (`CONCURRENT_STOCK_CHANGE`,
`CONCURRENT_MODIFICATION`) and `PAYMENT_RECONCILIATION_CONFLICT` → 409,
empty cart → 422, `PAYMENT_SERVICE_UNAVAILABLE` (reconciliation could not reach payment-service) → 503,
anything unexpected → 500. payment-service uses the same body shape.

### Users and ownership

See **Security (Phase 5)** below. Carts and orders are owned by the authenticated user (`app_user.shopping_session_id`
= the existing `session_id` columns); the browser never sends an owner id.

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
- **Concurrent duplicates:** since Phase 3 transaction 1 locks the session's cart row first, so a duplicate waits
  for the first request to commit and then finds its order by key (replay). As a safety net `CheckoutService` still
  returns the order for the key whenever placement fails for any reason (unique constraint, version, `CART_EMPTY`)
  and an order for that key exists; otherwise the failure is rethrown or translated (see Concurrency).
  (The `CART_EMPTY` race of Phase 2 was found by the smoke test.)
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
- `Product.version` (`@Version`) protects stock decreases in checkout; conflicts are translated to 409 (see Concurrency).
- `hibernate.order_updates: true`: UPDATEs of one flush are sorted by entity and id (consistent lock order).

## Concurrency (Phase 3) — production behaviour

Principle: **optimistic locking where a conflict is a real business event** (two shoppers, one product),
**short pessimistic row locks where the resource belongs to one owner and failing would be worse than waiting**
(one shopper's cart, one order's payment result), atomic SQL where a write must never fail (stock return).
Nothing is globally serialized, and no lock is ever held during a remote call.

| Resource | Strategy | Code | What a race produces |
|---|---|---|---|
| Product stock (purchase) | optimistic: entity + `@Version` | `Product.decreaseStock`, `OrderPlacementService` (tx 1) | loser's commit matches 0 rows → 409 `CONCURRENT_STOCK_CHANGE`, loser's transaction fully rolled back |
| Product stock (compensation) | atomic `UPDATE … + ?, version = version + 1` | `ProductRepository.increaseStock` | never fails; bumps the version so a purchase that read the old stock fails instead of overwriting it |
| Cart | pessimistic: `SELECT … FOR UPDATE` on the cart row for every mutation; `INSERT … ON CONFLICT DO NOTHING` to create | `CartRepository.findBySessionIdForUpdate` / `lockOrCreate`, `CartService`, `OrderPlacementService`, `OrderPaymentUpdater` | mutations of one cart run one after another: no lost update, no duplicate line, no unique-key 500 |
| Order payment result | pessimistic: `SELECT … FOR UPDATE` on the order row + final-state check | `OrderRepository.findByIdForUpdate`, `OrderPaymentUpdater.applyOutcome` | first outcome wins; later ones are ignored (`order.payment_outcome_ignored`), compensation runs at most once |

### Two shoppers, one last unit

```text
stock = 1, version = 7
tx A: SELECT product → stock 1, v7          tx B: SELECT product → stock 1, v7
tx A: UPDATE … stock=0, v=8 WHERE v=7 ✔     tx B: UPDATE … WHERE v=7  (waits for A's row lock)
tx A: COMMIT                                 tx B: re-checks → 0 rows → StaleObjectStateException → ROLLBACK
                                             → CheckoutService: 409 CONCURRENT_STOCK_CHANGE
```

- Exactly one order and one payment exist for the last unit; stock can never go negative (domain check +
  `CHECK (available_quantity >= 0)`); the loser's order insert, stock change and cart clearing are all rolled back.
- `CheckoutService.translate` maps the optimistic failure (transaction 1 only updates `Product`) to
  `CONCURRENT_STOCK_CHANGE` with a "review your cart and try again" message — no JPA/SQL details.
- `GlobalExceptionHandler` maps any other `ConcurrencyFailureException` (optimistic conflict, lock timeout, deadlock
  victim) to 409 `CONCURRENT_MODIFICATION` as a centralized safety net. Unexpected bugs stay 500.
- **Trade-off (measured):** optimistic locking conflicts on the *row*, not on the *quantity*. 10 shoppers racing for
  3 units: in our runs typically only 1 bought and 9 got 409 although units were left — correct, never oversold, but
  unfriendly for a hot product. Alternatives, not implemented: retry transaction 1 a few times on conflict (safe: it
  has no side effects outside the DB), or a conditional atomic `UPDATE … SET stock = stock - ? WHERE stock >= ?`
  (no false conflicts, but the stock check moves into SQL).

### Cart

Before Phase 3, 10 concurrent "+1" requests for the same line ended at quantity **2 instead of 11** (lost updates) and
concurrent first adds of a new session failed with 500 (unique `cart.session_id`). Now every mutation locks the cart
row first. A cart belongs to one shopper, so contention is tiny and waiting a few milliseconds is better than a
conflict error — in particular for the payment-failure restore, which cannot ask anyone to "try again".
The restore is idempotent through the order's final state (it runs only in the transaction that moves the order to
`PAYMENT_FAILED`, under the order row lock). Checkouts of the same session are also serialized by this lock.

### Lock order and lock scope

- Global lock order: **order row → cart row → product rows (ascending id)**. Transaction 1 locks cart, then updates
  products at commit (`hibernate.order_updates` sorts them by id); transaction 2 locks order, then cart, then returns
  stock in ascending product id; cart edits lock only the cart. No transaction takes these locks in the opposite
  order, so they cannot deadlock each other.
- All locks live inside the short transactions 1 and 2 and cart mutations. The payment call and the reconciliation
  lookup run with **no transaction, no DB connection and no row lock** — enforced by `PaymentClient.requireNoTransaction`
  and verified by `OrderPaymentRaceIntegrationTest.noRowLockOrTransactionIsHeldWhilePaymentServiceIsCalled`
  (`SELECT … FOR UPDATE NOWAIT` on order, cart and product rows succeeds while payment-service is handling the call).

### How the races are tested (deterministically)

`concurrency/RowLockHolder` holds a row lock in its own JDBC transaction. Application transactions can still *read*
the row (PostgreSQL MVCC) but queue as soon as they write/lock it; the test waits until `pg_stat_activity` shows the
expected number of lock waiters, then releases the lock. So both checkouts really have read "stock = 1" before either
writes — without any sleeps or hooks in production code. Tests: `LastItemCheckoutConcurrencyIntegrationTest`,
`StockUpdateConcurrencyIntegrationTest`, `CartConcurrencyIntegrationTest`, `OrderPaymentRaceIntegrationTest`.

## Concurrency lab (training only, `src/test/.../lab`)

Not part of the application: test sources only, no endpoints, no application executor changed. A deterministic fake
downstream (`lab/FakeDownstream`, fixed latency, optional concurrency limit) and a blocking HTTP client
(`lab/DownstreamClient`). Observed values are printed as `[LAB] …` lines when the tests run.

| Experiment | Code | Shows |
|---|---|---|
| 3 independent 300 ms calls: sequential / `CompletableFuture` / virtual threads | `ProductPageLoader`, `ParallelCallsLabTest` | ≈ 900 ms vs ≈ 300 ms vs ≈ 300 ms: concurrency cuts wall-clock time for independent blocking I/O; virtual threads are not faster than `CompletableFuture` on a pool, and neither is faster than the slowest remote call |
| explicit executor | `ProductPageLoader.newIoExecutor` | named platform pool `product-page-io-*`, never the implicit `ForkJoinPool.commonPool()` |
| failure / timeout | `ParallelCallsLabTest` | CF variant is **fail-fast** (fails in a few ms when one call fails; `allOf` alone would wait for the slowest; `cancel` does not interrupt the blocked thread); VT variant is **structured** (fails only after its siblings finished); `orTimeout` stops waiting at 500 ms |
| fixed pool 4, 20 × 200 ms | `ThreadPoolLabTest` | max 4 running, 16 queued, ≈ 1000 ms in 5 batches; virtual-thread-per-task: 20 running, ≈ 200 ms |
| 50 virtual threads → downstream limited to 5 | `VirtualThreadLimitsLabTest` | 50 calls in flight on the client, 5 processed at a time, ≈ 2000 ms: the downstream decides throughput |
| 30 virtual threads → DB pool of 10 | `DatabasePoolLimitLabTest` | ≤ 10 active connections, ~20 threads waiting for one, ≥ 600 ms |
| lock, CPU | `VirtualThreadLimitsLabTest` | a lock still serializes; CPU-bound work is not faster on virtual threads |

Virtual threads make *waiting* cheap (10 000 sleeping virtual threads finish in a few hundred ms). They do not add
database connections, HTTP connections, downstream capacity, rate limits, lock throughput or CPU cores. The production
checkout does not use them: its three steps are dependent, not independent, so there is nothing to parallelize.

## Events, transactional outbox and Kafka (Phase 4)

### Why not "save the order, then send to Kafka"?

A dual write to two systems cannot be atomic. Send inside the DB transaction → the transaction can still roll back
after Kafka accepted the message (event for an order that does not exist). Send after the commit → the process can die
between commit and send (order without event, lost forever). Either way DB and Kafka silently disagree.

**Transactional outbox:** the event is written as a row of the *same* database, in the *same* transaction as the
business change. Both commit or both roll back. A separate publisher later copies committed rows to Kafka.

```text
Order transaction (checkout tx 1 / payment tx 2)
    |
    +-- orders                       ┐ one COMMIT
    +-- outbox_event (pending)       ┘
          |
          v
     outbox publisher  (poll every 500 ms: claim batch FOR UPDATE SKIP LOCKED → send → mark published)
          |
          v
     Kafka marketplace.order-events  (key = orderId, headers eventId/eventType/schemaVersion)
          |
          v
     order-activity-service listener
          |
          +-- processed_event (eventId)  ┐ one local COMMIT, then the offset is committed
          +-- order_activity (+ entry)   ┘
          |
          +-- transient failure → retried 3× with backoff; permanent or exhausted → marketplace.order-events.DLT
```

### Event model

| Event | Written when (same transaction) | Payload (plus envelope) |
|---|---|---|
| `OrderCreated` | tx 1 creates the order (`OrderPlacementService`) | orderId, status `PAYMENT_PENDING`, total, currency, createdAt, lines |
| `OrderPaid` | transition to `PAID` (`OrderPaymentUpdater`) | orderId, status, total, currency, paymentId |
| `OrderPaymentFailed` | transition to `PAYMENT_FAILED` | orderId, status, total, currency, reason (`DECLINED`/`NOT_PROCESSED`), paymentId |
| `OrderPaymentUnknown` | transition to `PAYMENT_UNKNOWN` | orderId, status, total, currency |

`OrderPaymentUnknown` is included because it is a real, visible state ("payment being verified") that downstream
views should show; it is later followed by `OrderPaid` or `OrderPaymentFailed`.

Envelope (JSON, `outbox/EventEnvelope`): `eventId` (UUID), `eventType`, `schemaVersion` (1), `aggregateType` (`Order`),
`aggregateId`, `sequence` (1, 2, 3 per order), `occurredAt`, `payload`. Payloads are explicit records
(`order/events/OrderEvents`), never JPA entities, and self-contained. The anonymous session id is **not** included (it is a
bearer credential for the cart and orders). `Order` itself knows nothing about events or Kafka.

No event is written when nothing changed: a replayed checkout key, an outcome for an already final order (late checkout
thread, repeated reconciliation) and an "unknown → still unknown" reconciliation write no outbox row (tested).

**Serialization and compatibility.** JSON built once by the outbox writer and sent unchanged (`StringSerializer`); no
Schema Registry. Within a schema version changes are additive only; consumers ignore unknown fields (tolerant reader).
Removing/renaming/retyping a field requires a new `schemaVersion`, which consumers must support explicitly — unsupported
versions are rejected to the DLT, never guessed. The consumer has its own copy of the contract (no shared jar).

### Outbox table (`V4__transactional_outbox.sql`)

`id`, `event_id` (unique), `aggregate_type`, `aggregate_id`, `event_type`, `schema_version`, `sequence`
(unique per aggregate), `payload` (`JSON`: the exact message), `occurred_at`, `published_at` (NULL = pending),
`attempt_count`, `last_attempt_at`, `next_attempt_at` (backoff), `last_error`. A partial index covers only unpublished
rows, so polling stays cheap however large the table grows. Written with JDBC (`outbox/OutboxRepository`), which joins the
current JPA transaction; `OutboxWriter.append` is `Propagation.MANDATORY` — writing an event outside a transaction fails.

### Publication algorithm (`outbox/OutboxPublisher`)

```sql
-- one short transaction per batch (batch-size 50)
select … from outbox_event o
where o.published_at is null
  and (o.next_attempt_at is null or o.next_attempt_at <= clock_timestamp())
  and not exists (select 1 from outbox_event e            -- head of line per order
                  where e.aggregate_type = o.aggregate_type and e.aggregate_id = o.aggregate_id
                    and e.published_at is null and e.id < o.id)
order by o.id limit 50
for update of o skip locked;
-- for each row: send to Kafka and wait for the ack → mark published
-- on a send failure: attempt_count+1, last_error, next_attempt_at = now + backoff (1 s … 60 s); stop the batch
commit
```

- **Several publisher instances**: `FOR UPDATE SKIP LOCKED` — a row claimed by one instance is skipped (not waited for)
  by the others, so they work in parallel on different rows and never publish the same row at the same time
  (test `twoPublisherWorkersNeverClaimTheSameRow`). Nothing is globally serialized.
- **Ordering per order**: only the oldest unpublished event of an order is eligible. Without that, instance B could
  publish `OrderPaid` while instance A still holds `OrderCreated` of the same order (test
  `aSecondWorkerCannotOvertakeTheHeadEventOfAnAggregateHeldByTheFirst`). A failing event blocks only its own order.
- **Kafka down**: the send fails after `max.block.ms`; the row stays pending with the error and a backoff; the business
  transaction was committed long before and is unaffected; a later poll publishes it (test + smoke test).
- **The claim is held during the send.** This background job keeps its short transaction (and one connection) open while
  waiting for the broker, bounded by batch size and producer timeouts; it is never on a user request path. (A lease
  column instead of row locks would avoid that, at the cost of more moving parts.)
- **Crash window**: Kafka acknowledged the record, then the process dies (or the UPDATE fails) before `published_at` is
  committed → the claim rolls back → the row is sent **again** later. Test `crashAfterSendBeforeMarkPublishesTheSameEventTwice`
  shows two Kafka records with the same `eventId`. The outbox guarantees *never lost*, not *exactly once*.
- Scheduling is a separate bean (`outbox.publisher.enabled`); poll interval, batch size, send timeout and backoff are
  configurable (`outbox.*`).

### Topic, key and ordering

One topic `marketplace.order-events` (3 partitions; name configurable) for all order events — consumers usually need
the whole lifecycle of an order in order. Key = `orderId` → all events of an order land in the same partition, and Kafka
keeps order **within a partition**. There is no global order: different orders are on different partitions and are
processed in parallel (one listener thread per partition). The idempotent producer (`enable.idempotence`,
`acks=all`) keeps the producer's own internal retries from duplicating or reordering records within a partition.

Partition ordering does not protect against: a manually produced or replayed message, a future topic migration or
repartitioning (changing the partition count remaps keys), or workflows spanning several topics. So the consumer does not
trust order blindly: it checks `sequence` (older → ignored as stale) and validates state transitions (impossible →
DLT).

### Producer configuration (marketplace `application.yaml`)

`acks=all` (all in-sync replicas), `enable.idempotence=true`, retries left at the default and bounded by
`delivery.timeout.ms=10000`, `request.timeout.ms=5000`, `max.block.ms=5000`, String key/value serializers. The
publisher waits synchronously for each ack (`send-timeout` 12 s ≥ delivery timeout).

### Consumer: `order-activity-service`

A small separate Spring Boot service (Java 25, spring-kafka, JDBC, Flyway) that builds an "order activity" view:
`order_activity` (current status, total, last event id and sequence) and `order_activity_entry` (history like "Payment
confirmed"). Own schema `order_activity` in the same PostgreSQL server locally (own tables, own Flyway history; it never
reads the marketplace tables) — a separate database in production.

**Transaction boundary and idempotency** (`activity/OrderActivityProjector.apply`, one `@Transactional` method):

```text
INSERT INTO processed_event(event_id) ON CONFLICT DO NOTHING   -- 0 rows → duplicate → return, nothing else happens
SELECT order_activity … FOR UPDATE
sequence <= last_sequence → stale, ignore
transition not allowed    → InvalidStateTransitionException (permanent)
INSERT/UPDATE order_activity, INSERT order_activity_entry
COMMIT
```

The listener returns only after that commit; with `ack-mode: record` and auto-commit off, the container then commits the
offset. A crash between DB commit and offset commit redelivers the record → found in `processed_event` → skipped. If
anything fails before the commit, everything rolls back including the `processed_event` row, and Kafka redelivers
(test `transientFailureIsRetriedAndTheFailedAttemptsLeaveNoTrace`). Kafka offsets alone cannot provide business
idempotency: the outbox itself may publish an event twice. No XA, no Kafka transactions.

**Retry and DLT** (`kafka/KafkaConsumerConfiguration`): `DefaultErrorHandler` with `ExponentialBackOffWithMaxRetries(3)`
(200 ms, 400 ms, 800 ms) for transient failures (any exception except `PermanentEventException`: e.g. a DB hiccup,
`SimulatedTransientFailure`). Permanent failures — malformed JSON, missing/invalid required field, unsupported
`schemaVersion`/type, impossible transition — are not retried. After that the `DeadLetterPublishingRecoverer` publishes
the original record to `marketplace.order-events.DLT` (same partition) with headers `kafka_dlt-original-topic/-partition/
-offset/-timestamp`, `kafka_dlt-exception-cause-fqcn`, `…-exception-message` (the stack-trace header is dropped); the
offset is committed and the partition continues with the next record (test
`exhaustedRetriesGoToTheDeadLetterTopicAndLaterRecordsAreStillProcessed`). Retries happen in place, so the partition
waits during backoff: order per order is kept, at the cost of head-of-line blocking of that partition for ~1.4 s.

**Failure simulation** (dev/test only): `simulation/FailureSimulator` fails the next N attempts of events matching an
order id or event type, *after* all writes and before the commit (proves rollback). Exposed over HTTP only with
`order-activity.simulation.enabled=true` (`SimulationController`).

### Eventual consistency

```text
checkout HTTP response   ← marketplace DB committed (order + outbox rows pending)
~0–500 ms later          ← outbox publisher sends, marks published
milliseconds later       ← consumer commits processed_event + projection, then the offset
```

The order write never waits for Kafka or the consumer; `GET /api/order-activity/{id}` may briefly show an older status
or 404. If Kafka is down, the lag grows until it is back; nothing is lost. The frontend does not use the projection.

### Logging

`outbox.created`, `outbox.publish_attempt`, `outbox.sent` (topic/partition/offset), `outbox.published`,
`outbox.publish_failed`, `outbox.poll_failed`; `event.received` (topic/partition/offset/attempt), `event.duplicate`,
`event.stale`, `event.processed`, `event.retry`, `event.failed permanent=true`, `event.dead_lettered` — all with eventId,
eventType and order id, no payload bodies.

## Security (Phase 5)

### Authentication vs authorization

- **Authentication** — *who are you?* Email + password once (`POST /api/auth/login` or `/register`), then a
  server-side session identified by the `MARKETPLACE_SESSION` cookie. No/expired session → **401**.
- **Authorization** — *what may you do?* Here it is resource ownership: a cart or order is accessible only to its owner.
  Every repository query is scoped by the owner key from the authenticated principal. Someone else's order → **404**
  (not 403: do not even confirm that order #42 exists). Authenticated but not allowed (e.g. wrong/missing CSRF token) → **403**.

### One authentication model: server-side session (not JWT)

Spring Security + **Spring Session JDBC** (sessions in PostgreSQL) + an **HttpOnly cookie**.

| | Server-side session (chosen) | JWT access token |
|---|---|---|
| Where state lives | `SPRING_SESSION` table; cookie holds only a random id | self-contained signed token in the client |
| Logout / revocation | delete the row — immediate | token valid until expiry unless a denylist (= server state again) |
| Browser storage | HttpOnly cookie: unreadable by JavaScript/XSS | often `localStorage` (readable by XSS) or a cookie (then CSRF anyway) |
| CSRF | needed (cookie sent automatically) — implemented | not needed for `Authorization: Bearer` headers (not sent automatically) |
| Scaling | shared store needed (here PostgreSQL; every instance reads it) | stateless verification, good across many services/domains |
| Fits | one browser app + its own backend | APIs for third parties, mobile, service chains, federated identity |

For one browser SPA talking to its own backend, the session is simpler and safer (instant logout, no token in JS). JWT was
not added "because it is popular": a second auth path would double the attack surface and the tests without solving a
problem this system has. The internal service-to-service calls use a bearer header (see below), which is where bearer
tokens make sense.

### User model (`V5__app_user.sql`, `auth/AppUser`)

`app_user(id identity, email unique + CHECK normalized, password_hash, shopping_session_id UUID unique, created_at)`.
- Email normalized (trim + lower case, `auth/EmailAddress`) before validation, on register and login; the DB constraint
  rejects any non-normalized value.
- Password: `@Size(min = 8, max = 72)` and at most **72 bytes** (BCrypt ignores anything longer —
  `UserRegistrationService.MAX_PASSWORD_BYTES`), hashed with **BCrypt** (`BCryptPasswordEncoder`, cost 10, salted);
  verified only via `PasswordEncoder.matches` (inside `DaoAuthenticationProvider`). Hash never in DTOs, logs or the session.
- **`shopping_session_id`** is the owner key the cart and order tables already use (`session_id` columns). It is a random
  UUID created once per user, stable across logins, never sent to the browser. So **no cart/order migration** was needed;
  `SessionId` and all services stay unchanged — only `session/SessionIdArgumentResolver` now takes it from the principal.

### The principal and the removed `X-Session-Id`

`auth/AuthenticatedUser(userId, email, shoppingSessionId)` is stored in the session (Serializable, no hash; `getName()`
= user id, so `SPRING_SESSION.PRINCIPAL_NAME` and logs hold no email). No user lookup per request.
Phases 1–4 let the browser choose the owner id (`X-Session-Id`) — anyone who knew or guessed it owned that cart. The header
is gone everywhere (resolver, OpenAPI, frontend, tests) and there is **no fallback**: an anonymous request with a victim's
owner key gets 401, a logged-in user sending it simply sees their own data (`AuthorizationIntegrationTest.theLegacyXSessionIdHeaderGrantsNothing`).

### Sessions (`V6__spring_session.sql`) and the cookie

- Spring Session JDBC with Flyway-owned tables (`spring.session.jdbc.initialize-schema: never`); any marketplace instance
  reads the same sessions. Idle timeout `spring.session.timeout` (`SESSION_TIMEOUT`, default 30 min; tests prove the
  configured value is applied); Spring Session deletes expired sessions.
- Cookie (`SecurityConfiguration.sessionCookieSerializer`, explicit — Spring Session's default would be a cookie named
  `SESSION` without our settings): `MARKETPLACE_SESSION`, `HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure` configurable
  (`SESSION_COOKIE_SECURE`, default **true**; `false` only for plain-HTTP local development). Production is expected behind
  HTTPS/TLS.
- **Session fixation**: after login/registration `ChangeSessionIdAuthenticationStrategy` gives the session a new id; an id
  known before login (planted by an attacker) is worthless afterwards (tests: id changes, old id → 401; a planted cookie is
  never adopted).
- **Logout** (`POST /api/auth/logout`, needs CSRF): `SecurityContextLogoutHandler` invalidates the session → the row is
  deleted; the old cookie no longer authenticates (tested, also in the smoke test). **Expiry**: an idle session is rejected
  (tested by moving `last_access_time` back).

### CSRF

With cookie authentication the browser attaches the session cookie to every request to our site — also to a request a
malicious page triggers (form POST, `fetch` with credentials). CSRF protection makes such forged requests fail:
- `CookieCsrfTokenRepository` (via `csrf.spa()`): token in the **readable** `XSRF-TOKEN` cookie (`SameSite=Lax`, `Path=/`);
  the SPA copies it into the **`X-XSRF-TOKEN` header** on POST/PUT/DELETE. Another site can make the browser *send* our
  cookies but cannot *read* them, so it cannot set the header. The session cookie itself stays HttpOnly.
- All unsafe methods need it — including **login and register** (login CSRF: a foreign page logging the victim into the
  attacker's account) and logout. GET/HEAD/OPTIONS don't. `GET /api/auth/csrf` makes sure the cookie exists.
- Login/registration clear the token (`CsrfAuthenticationStrategy`); the client fetches a fresh one. This repository is the
  **stateless double-submit** pattern: the server only checks header == cookie and keeps no copy, so "rotation" means the
  browser drops the cookie. It relies on attackers being unable to write our cookies; a compromised subdomain could
  ("cookie tossing") — mitigations would be a `__Host-` cookie prefix (requires HTTPS) or a session-bound token repository.
- **Why bearer-header APIs differ**: an `Authorization: Bearer` header is never added by the browser automatically, so a
  pure header-token API is not CSRF-prone (its risk is token theft, e.g. from `localStorage` via XSS).
- Missing/wrong token → **403 `CSRF_FAILED`**; the frontend then refreshes the token and retries once.

### CORS and the same-origin policy

The browser's same-origin policy stops JavaScript of origin A from reading responses of origin B. In development the Vite
proxy makes the API same-origin, so CORS is not even needed. For a frontend on another origin, CORS
(`SecurityConfiguration.corsConfigurationSource`) allows only `APP_CORS_ALLOWED_ORIGINS` (default `http://localhost:5173`),
with `allowCredentials=true` — therefore the origin is echoed exactly and `*` is rejected at startup; allowed methods
GET/POST/PUT/DELETE/OPTIONS and headers `Content-Type`, `Accept`, `X-XSRF-TOKEN`, `Idempotency-Key`, `X-Payment-Scenario`.
Unknown origin → 403, no `Access-Control-Allow-Origin` (tested). CORS is not CSRF protection: CORS controls who may *read*
responses; a forged form POST needs no CORS at all — that is what the CSRF token is for.

### Error model and headers

`auth/SecurityErrorHandler` writes the normal `ApiError` JSON for Spring Security failures — no HTML, no redirect:
`401 AUTHENTICATION_REQUIRED`, `401 INVALID_CREDENTIALS` (identical for unknown email and wrong password;
`DaoAuthenticationProvider` also hashes a dummy password for unknown users so timing does not tell), `403 ACCESS_DENIED`,
`403 CSRF_FAILED`, `409 EMAIL_ALREADY_REGISTERED`, `400 INVALID_PASSWORD`/`VALIDATION_FAILED`.
Spring Security's default headers are kept: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Cache-Control: no-cache, no-store` (authenticated JSON must not be cached by shared caches), HSTS only on HTTPS requests.
No CSP: the backend serves JSON only; the frontend is served separately. Swagger UI / OpenAPI stay public in this training
project (they contain no data).

### Logging

`auth.registered userId=…`, `auth.login_succeeded userId=…`, `auth.login_failed reason=…` (no email), `auth.logout
userId=…`, `security.authentication_required path=…`, `security.access_denied userId=… path=…`,
`security.csrf_rejected userId=… path=…`, `security.service_auth_failed code=…` (payment-service),
`security.internal_api_auth_failed` (order-activity-service). Never logged: passwords, hashes, session cookies, CSRF tokens,
service tokens, `Authorization` headers (tests capture the log output and check for the password; the smoke-test logs
were scanned for all of them).

### Service-to-service authentication

- **marketplace → payment-service**: `PaymentClient` sends `Authorization: Bearer <PAYMENT_SERVICE_TOKEN>` on every
  payment POST (including retries) and reconciliation GET; payment-service's `security/ServiceTokenFilter` rejects a
  missing or wrong token with JSON 401 (`MISSING_SERVICE_TOKEN` / `INVALID_SERVICE_TOKEN`), comparing in constant time
  (`MessageDigest.isEqual`). A 401 is a 4xx: the marketplace treats it as "not processed" (no retry, order fails, nothing
  charged). Retry, idempotency and reconciliation are unchanged.
- **order-activity-service**: its HTTP API (projection read, dev-only simulation) requires `Authorization: Bearer
  <ORDER_ACTIVITY_API_TOKEN>` — a different secret. The simulation controller still exists only with
  `order-activity.simulation.enabled=true`, and even then never anonymously (tested). The Kafka consumer is unaffected.
- Both tokens have `dev-only-…` defaults for local startup (a warning is logged); real environments set the variables.
- **Limits of a shared secret**: no identity per caller, manual rotation (both sides at once), leaks via config/logs,
  replayable if intercepted without TLS. Real systems use mTLS, workload identity (e.g. SPIFFE, cloud IAM), or OAuth2
  client credentials with short-lived tokens — deliberately not built here.

### Kafka security boundary (not implemented)

The local Kafka runs PLAINTEXT, single broker, **no authentication, no TLS, no ACLs — not production-safe**. Anyone who
can reach port 9092 can read or write order events. Production Kafka would use TLS + SASL (SCRAM/OAUTHBEARER) or workload
identity, and ACLs such as: marketplace-service may *produce* to `marketplace.order-events`; order-activity-service may
*consume* it (group `order-activity-service`) and *produce* to `marketplace.order-events.DLT`. Standard
`spring.kafka.security.*` / `spring.kafka.properties.sasl.*` properties could be supplied via environment, but nothing
of that is configured or tested here.

## Frontend: `marketplace-web`

React 19 + TypeScript + Vite, plain CSS, no UI framework, no router, no global state library.

- **Authentication**: `App.tsx` keeps `auth` state (`loading` / `anonymous` / `authenticated`). On start it calls
  `/api/auth/me` — the browser still has the HttpOnly session cookie after a reload, so a valid session is restored.
  Logged out: catalog visible, "Log in to buy", `components/AuthPanel` (login / create account; the password is cleared
  from state after every submit). Any 401 `AUTHENTICATION_REQUIRED` (expired session) returns to the logged-out view.
  Nothing about the login is stored in `localStorage`; the old `session.ts` (random `X-Session-Id`) is deleted.
- **CSRF** is centralized in `api/client.ts`: unsafe requests read the `XSRF-TOKEN` cookie (fetching `/api/auth/csrf`
  first if it is missing, e.g. after login) and send `X-XSRF-TOKEN`; a `403 CSRF_FAILED` triggers one token refresh + retry.
  All requests use `credentials: 'include'`.
- `App.tsx` owns the cart and the current view. It keeps the **checkout attempt key** in a `useRef`: created on
  the first click of an attempt, reused when the request failed in a way that may have reached the backend
  (network error, 5xx), cleared after an order came back or the backend rejected the request (4xx).
  After checkout it reloads the cart (empty after success, restored after a failed payment). After a stock-related
  rejection (`CONCURRENT_STOCK_CHANGE`, `INSUFFICIENT_STOCK`, `PRODUCT_UNAVAILABLE`) it reloads cart and catalog; the
  409 is definitive (nothing was ordered), so the next click is a new attempt with a new key.
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
open circuit), Phase 3 race tests in `concurrency/` (last unit, stock paths, cart, order outcome vs reconciliation,
lock scope), `common/ConcurrencyErrorMappingTest`, the training lab in `lab/`, and Phase 4 outbox tests in `outbox/`
(`OrderOutboxEventsIntegrationTest`: which events each transaction writes, rollback, no duplicates;
`OutboxPublisherIntegrationTest`: real Kafka — keyed/ordered publication, Kafka unavailable, crash window, concurrent
workers, head of line; `OutboxSchedulingIntegrationTest`: the scheduled publisher; `OutboxWriterTest`: serialization).
All integration tests share one PostgreSQL and one Kafka container (Testcontainers, `apache/kafka:4.1.1`).

Phase 5 security tests in `auth/` (real security chain via `TestBrowser`, a cookie jar that copies the CSRF cookie into
the header like the SPA): `AuthenticationIntegrationTest` (register, normalization, hash, cookie flags, duplicate email,
login, identical failures, validation, no secrets in logs), `SessionLifecycleIntegrationTest` (JDBC session, timeout,
logout, fixation, planted id, CSRF reset, expiry), `CsrfIntegrationTest`, `AuthorizationIntegrationTest` (public catalog,
401s, `X-Session-Id` ignored, cart/order isolation, same idempotency key for two users, no owner key in responses),
`CorsAndHeadersIntegrationTest`. All older integration tests now sign up a real user per test.

order-activity-service (`mvn test`): `OrderEventParserTest` (contract, tolerant reader, malformed/unsupported),
`OrderActivityConsumerIntegrationTest` (real Kafka + PostgreSQL: eventual projection, ordering, duplicate delivery,
transient retry with rollback, exhausted retries → DLT and continue, malformed/unsupported/impossible transition → DLT,
stale replay ignored).

payment-service (`mvn test`): `PaymentServiceTest` (idempotency incl. 32 concurrent threads), `ScenarioSimulatorTest`,
`PaymentApiIntegrationTest` (real HTTP on a random port: every scenario, 16 concurrent HTTP requests with one key),
`ServiceTokenIntegrationTest` (missing/wrong/right token, lookups protected, token not logged). order-activity-service also has
`InternalApiSecurityIntegrationTest`.

Frontend (`npm test`, Vitest + Testing Library): idempotency key reuse per attempt, one request per double click,
rendering of paid/declined/technical failure/unknown, reconciliation button, reload + new attempt after
`CONCURRENT_STOCK_CHANGE`. Phase 5: CSRF header on unsafe calls (login, logout, cart, checkout), token fetch when missing,
retry after `CSRF_FAILED`, no `X-Session-Id`, credentials included, logged-out view, login (password cleared), register,
session restore via `/me`, logout, 401 → logged-out view.

## Not implemented yet (deliberately)

- **Automatic reconciliation / expiry:** no scheduler. An order whose payment is never found stays
  `PAYMENT_UNKNOWN` with its stock taken until someone reconciles it again. Resolving "never found" (e.g. resubmitting
  the payment with the same key, or voiding it after a grace period) needs a provider-side contract and is deferred.
- **Durable payment storage:** payment-service is in memory; a restart forgets payments (and reconciliation would then
  find nothing).
- **Asynchronous payment:** the payment call is still synchronous HTTP inside the checkout request; Kafka carries only
  downstream notifications of state changes. No saga, no Kafka-based payment commands, no CDC/Debezium, no Schema Registry.
- **Outbox housekeeping:** published outbox rows are kept forever (no retention job); a row that never publishes is
  retried with capped backoff forever (visible in `last_error`, no alerting). Processed-event ids are kept forever too.
- **DLT handling:** records in `marketplace.order-events.DLT` are only stored; no replay tool or alerting.
- **Stock reservation / server-side retry of optimistic conflicts:** a conflicting buyer gets 409 and must retry, even
  when enough units were left (false conflict on a hot product). See Concurrency for the alternatives.
- **Security extras:** no login rate limiting / account lockout, no password reset or email verification, no MFA, no
  OAuth/external IdP, no roles beyond "user", no Kafka authentication/ACLs, no mTLS between services (see Security).
- **Operations:** no metrics, tracing, Actuator/health checks, containerized services or deployment.
- Catalog management, pagination, stock reservation with expiry, multiple currencies, taxes, shipping.
