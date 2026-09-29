# Interview map

Where each interview topic can be demonstrated in the **current** code (Phases 1–2).
Topics for later phases are listed at the bottom as planned only. They have no code yet.

Paths are relative to `marketplace-service/src/main/java/pl/dch/marketplace/` (backend),
`payment-service/src/main/kotlin/pl/dch/payment/` (Kotlin service)
and `marketplace-web/src/` (frontend) unless stated otherwise.

## Java / backend

| Topic | Where | What to talk about |
|---|---|---|
| Stream API: map / toList | `checkout/OrderPlacementService.placeOrder`: cart items → `OrderLine`s | Pure transformation in the stream; the side effect (decreasing stock) is deliberately a plain `for` loop afterwards. Why side effects in `map` are a smell. |
| Stream API: reduce | `order/Order.calculateTotal`, `cart/CartService.toResponse` | `reduce(BigDecimal.ZERO, BigDecimal::add)`; identity value; why `sum()` does not exist for `BigDecimal`. |
| Collectors.toMap / HashMap lookup | `OrderPlacementService.placeOrder`, `CartService.toResponse` | One `findAllById` query + `Map<Long, Product>` gives O(n) instead of an O(n·m) nested search or n queries. What happens with duplicate keys in `toMap`. |
| Streams vs loops | `OrderPlacementService`: stream for lines, loop for stock | Readability, side effects, debugging, exceptions inside lambdas. Good exercise: rewrite both ways and compare. |
| Collections / encapsulation | `cart/Cart.getItems`, `order/Order.getLines` | `Collections.unmodifiableList` view vs copy; aggregate root controls all changes. |
| Complexity | `Cart.findItem` (linear scan) vs map lookup in checkout | When a linear scan over a handful of cart items is fine and when it is not. |
| Money / BigDecimal | `product/Product.price`, `order/OrderLine`, `OrderPlacementServiceTest.calculatesTotal…`, `Payment.matches` (Kotlin, `compareTo` for amounts) | Never `double` (the test uses `0.10 × 3`). `compareTo` vs `equals` (scale!), `isEqualByComparingTo` in tests, `NUMERIC(12,2)` in DB, JSON numbers becoming JS doubles. |
| Records / value objects | `session/SessionId`, all `*Response` / `*Request` DTOs | Immutability, compact constructor validation, `equals`/`hashCode` for free. |
| Entities vs DTOs | `*Response.from(...)`, `open-in-view: false` | Why entities are not API contracts: lazy loading, leaking internals (e.g. `version`), contract stability. |
| REST design | controllers in `product`, `cart`, `checkout`, `order` | Resource naming, PUT (absolute quantity) vs POST (add), idempotent DELETE, 201 + `Location` on checkout, 404 vs 403 for foreign orders. |
| Validation | `cart/AddCartItemRequest`, `cart/UpdateCartItemRequest`, `@Valid` in `CartController` | Bean Validation at the boundary **plus** domain guards in `CartItem.requireValidQuantity`. Wrapper `Integer` + `@NotNull` to detect missing fields. |
| Error handling | `common/GlobalExceptionHandler`, `ErrorCode`, `ApiError` | One error shape; status mapping in one place; extending `ResponseEntityExceptionHandler`; not leaking stack traces; logging only unexpected errors. |
| Transactions | `OrderPlacementService.placeOrder` (`@Transactional`), `ProductService` / `OrderService` (`readOnly = true`) | Atomicity of order placement, rollback on `RuntimeException`, self-invocation/proxy pitfalls (why the two checkout transactions live in separate beans), why `readOnly`. Proven by the rollback integration test. |
| JPA mapping | `cart/Cart` ↔ `CartItem`, `order/Order` ↔ `OrderLine` | `mappedBy`, `cascade`, `orphanRemoval` (removing from the list deletes the row), `@OrderBy`, `@Enumerated(STRING)`, `updatable = false`. |
| Dirty checking vs bulk update | `OrderPlacementService`: `product.decreaseStock(...)` without `save`; `ProductRepository.increaseStock` (`@Modifying` JPQL) | Managed entities are flushed at commit; an atomic `UPDATE … SET x = x + ?` bypasses the persistence context and cannot lose updates. |
| N+1 queries | `CartRepository.findBySessionId`, `OrderRepository` (`@EntityGraph`) | Fetching children together with the parent; alternatives (JOIN FETCH, batch size). |
| Optimistic locking (preparation) | `Product.version` (`@Version`) | What Hibernate does with it, what exception appears, why it is not handled yet (Phase 3 exercise). |
| Schema migrations | `resources/db/migration/V1__…`, `V2__…`, `V3__payment_integration.sql`, `ddl-auto: validate` | Flyway vs `ddl-auto=update`, never editing applied migrations, unique and `CHECK` constraints as the last line of defence, why no FK on `cart_item.product_id`. |
| Dependency injection | every service/controller | Constructor injection only, no field injection, easy unit testing without Spring. |
| Replaceable boundary | `session/SessionIdArgumentResolver` | One place to swap anonymous session for real auth; an abstraction with a concrete reason. |
| Testing strategy | `src/test/java/...` | Mockito unit tests for rules; Testcontainers + real PostgreSQL for persistence/API; context caching shares one container; a real local HTTP server (`payment/FakePaymentServer`) instead of mocking the HTTP client. |

## Distributed systems / resilience (Phase 2)

| Topic | Where | What to talk about |
|---|---|---|
| Remote call outside the DB transaction | `checkout/CheckoutService.checkout` (not `@Transactional`) → `OrderPlacementService` (tx 1) → `PaymentClient.pay` (no tx) → `OrderPaymentUpdater.applyOutcome` (tx 2); guard `PaymentClient.requireNoTransaction`; test `PaymentCheckoutIntegrationTest.orderIsCommittedBeforePaymentServiceIsCalled` | Why a slow dependency must not hold a DB connection (pool exhaustion, lock time); what each intermediate state means; what happens if the process dies between the steps. |
| HTTP client + timeouts | `payment/PaymentClientConfiguration.restClient`, `payment.client.*` in `application.yaml`, `PaymentClientProperties` (`@Validated`, no defaults) | Connect vs read/response timeout; why defaults (often infinite) are dangerous; `RestClient` over JDK `HttpClient`; test `PaymentClientTest.responseTimeoutLeavesTheResultUnknownAndIsNotRetried`. |
| Retry with backoff + jitter | `PaymentClientConfiguration.retry`, `PaymentClient.isRetryable`; tests `PaymentClientTest.transient503IsRetriedWithTheSameIdempotencyKey`, `…persistent503…`, `…internalServerErrorIsNotRetried…` | What to retry (connect failures, 502/503/504) and what not (declines, 4xx, 500, read timeouts); exponential backoff, jitter against thundering herd; retry is only safe because of the idempotency key. |
| Circuit breaker | `PaymentClientConfiguration.circuitBreaker`, decoration order in `PaymentClient.decorate`; tests `PaymentClientTest.circuitOpens…`, `failedHalfOpenCall…`, `clientErrorsDoNotOpenTheCircuit`, `PaymentCheckoutIntegrationTest.openCircuitFailsFast…` | CLOSED/OPEN/HALF_OPEN, sliding window, minimum calls, which failures count; Retry(CircuitBreaker(call)) vs the other way round; core API vs annotations/AOP. |
| Idempotency (server side) | Kotlin `payments/InMemoryPaymentRepository.saveIfAbsent` (`ConcurrentHashMap.computeIfAbsent`), `PaymentService.createPayment` (conflict check); tests `PaymentServiceTest.concurrent…`, `PaymentApiIntegrationTest.concurrent HTTP requests…` | Same key → same result; same key + different data → 409; why check-then-insert is a race and how `computeIfAbsent` / a unique constraint closes it. |
| Idempotency (client → marketplace) | `checkout/CheckoutController` (`Idempotency-Key`), `OrderPlacementService.placeOrder` (lookup by key), `V3__payment_integration.sql` (`UNIQUE (session_id, checkout_idempotency_key)`), `CheckoutService.checkout` (catch → look up the winner); tests `…sameCheckoutKey…`, `…concurrentDuplicateCheckouts…` | Why a frontend guard is not enough; key per attempt vs per cart; replay returns the current state (200) instead of repeating side effects; the `CART_EMPTY` race the smoke test found. |
| Distributed failure: unknown result | `payment/PaymentOutcome` (`Succeeded`/`Declined`/`NotProcessed`/`Unknown`), `PaymentClient` failure classification table, payment-service scenario `SUCCESS_BUT_SLOW_RESPONSE` | A timeout does not mean failure; "provably not processed" vs "unknown"; the 503 contract; why unknown keeps the stock and never marks the order failed. |
| Recovery / reconciliation | `checkout/PaymentReconciliationService`, `POST /api/orders/{id}/reconcile-payment`, `OrderPaymentUpdater` (row lock, final states ignored); test `…timeoutAfterRecordedPaymentLeavesOrderUnknownUntilReconciliationMarksItPaid` | Querying by idempotency key; "not found" is not proof of failure (`SLOW`); verifying the found payment belongs to the order; explicit vs scheduled reconciliation; concurrent checkout vs reconciliation. |
| State machine in the domain | `order/OrderStatus.canTransitionTo`, `Order.mark*`; test `OrderTest.allowedTransitions` | Encapsulated transitions instead of setters; final states; DB `CHECK` constraints as a second guard. |
| Consistency without distributed transactions | `OrderPaymentUpdater.returnItems`, `ProductRepository.increaseStock`, `Cart.restoreItem` | Local transactions + explicit intermediate states instead of 2PC/saga framework; compensation (return stock, restore cart) only when the result is definitive. |
| Structured logging | `payment.*`, `checkout.*`, `order.payment_status`, `reconciliation.*` log lines | key=value logs that can be correlated by order id / idempotency key; what not to log (card data, secrets). |
| Kotlin service | `payment-service/`: data classes (`Payment`, DTOs), nullable request fields + `@field:` validation targets, `when`, `?:` / `?.`, `runApplication`, `kotlin-maven-plugin` with the `spring` (all-open) plugin | Kotlin vs Java for Spring: final-by-default and proxies, null safety with JSR-305, Jackson Kotlin module, `object`/`companion` for constants and loggers. |
| Deterministic failure simulation | Kotlin `simulation/ScenarioSimulator` | Test hooks kept out of business code; no random failures; header forwarding only when explicitly enabled. |

## Frontend

| Topic | Where | What to talk about |
|---|---|---|
| React state | `App.tsx` (`cart`, `view`) | Lifting state to the closest common parent; discriminated union for the view; server response as the single source of truth. |
| useEffect for API sync | `App.tsx` (cart), `components/ProductList.tsx` (products) | Cleanup with `AbortController`, StrictMode double-mount in dev, ignoring aborted requests, loading/error states. |
| Local component state | `components/CartPanel.tsx` (`CartRow` draft quantity) | Controlled input as a draft; stable `key` (product id) plus adjusting state during render when the server quantity changes, instead of a remount or an effect. |
| Duplicate submit | `CartPanel.handleCheckout`, `ProductList.handleAdd` | Disabled button **and** `useRef` guard; why state alone is not synchronous. Tests: `CartPanel.test.tsx`, `App.test.tsx` (one request per double click). |
| Idempotency key per attempt | `App.tsx` (`checkoutAttemptKey` ref, `mayHaveBeenProcessed`) | New key per attempt, same key when retrying after a network error/5xx, new key after a 4xx; why a `useRef`, not state. Test: `App.test.tsx`. |
| Rendering async results | `components/OrderConfirmation.tsx` | Paid / declined / technical failure / "Payment status is being verified." + reconciliation button; exhaustive `switch` over a string union. |
| API error handling | `api/client.ts` | Typed `ApiError`, backend error body vs non-JSON error vs network failure, user-readable messages. |
| Session identity in the browser | `session.ts` | `crypto.randomUUID`, `localStorage` failure fallback, validating stored data. |
| TypeScript contracts | `api/types.ts` | Mirroring backend DTOs; nullable fields for missing products. |
| Dev proxy vs CORS | `vite.config.ts` | Why no CORS config is needed in dev; what changes when frontend and API are on different origins. |

## Planned topics (no code yet)

Listed only to show where they will attach. Nothing below is implemented.

| Topic | Planned phase / place |
|---|---|
| Scheduled reconciliation, expiry of never-found payments | later (see architecture.md, "Not implemented yet") |
| Race condition on the last item, optimistic lock conflict handling | Phase 3: checkout + `Product.version` |
| Thread pools, `CompletableFuture`, virtual threads, JMM | Phase 3 |
| Kafka, transactional outbox, consumer idempotency | Phase 4 |
| Authentication, authorization, sessions vs JWT, CORS/CSRF | Phase 5: replaces `SessionIdArgumentResolver` |
| Heap, GC, connection pool diagnostics | Phase 6 |
