# Interview map

Where each interview topic can be demonstrated in the **current** code (Phase 1).
Topics for later phases are listed at the bottom as planned only. They have no code yet.

Paths are relative to `marketplace-service/src/main/java/pl/dch/marketplace/` (backend)
and `marketplace-web/src/` (frontend) unless stated otherwise.

## Java / backend

| Topic | Where | What to talk about |
|---|---|---|
| Stream API: map / toList | `checkout/CheckoutService.checkout`: cart items → `OrderLine`s | Pure transformation in the stream; the side effect (decreasing stock) is deliberately a plain `for` loop afterwards. Why side effects in `map` are a smell. |
| Stream API: reduce | `order/Order.calculateTotal`, `cart/CartService.toResponse` | `reduce(BigDecimal.ZERO, BigDecimal::add)`; identity value; why `sum()` does not exist for `BigDecimal`. |
| Collectors.toMap / HashMap lookup | `CheckoutService.checkout`, `CartService.toResponse` | One `findAllById` query + `Map<Long, Product>` gives O(n) instead of an O(n·m) nested search or n queries. What happens with duplicate keys in `toMap`. |
| Streams vs loops | `CheckoutService`: stream for lines, loop for stock | Readability, side effects, debugging, exceptions inside lambdas. Good exercise: rewrite both ways and compare. |
| Collections / encapsulation | `cart/Cart.getItems`, `order/Order.getLines` | `Collections.unmodifiableList` view vs copy; aggregate root controls all changes. |
| Complexity | `Cart.findItem` (linear scan) vs map lookup in checkout | When a linear scan over a handful of cart items is fine and when it is not. |
| Money / BigDecimal | `product/Product.price`, `order/OrderLine`, `CheckoutServiceTest.calculatesTotal…` | Never `double` (the test uses `0.10 × 3`). `compareTo` vs `equals` (scale!), `isEqualByComparingTo` in tests, `NUMERIC(12,2)` in DB, JSON numbers becoming JS doubles. |
| Records / value objects | `session/SessionId`, all `*Response` / `*Request` DTOs | Immutability, compact constructor validation, `equals`/`hashCode` for free. |
| Entities vs DTOs | `*Response.from(...)`, `open-in-view: false` | Why entities are not API contracts: lazy loading, leaking internals (e.g. `version`), contract stability. |
| REST design | controllers in `product`, `cart`, `checkout`, `order` | Resource naming, PUT (absolute quantity) vs POST (add), idempotent DELETE, 201 + `Location` on checkout, 404 vs 403 for foreign orders. |
| Validation | `cart/AddCartItemRequest`, `cart/UpdateCartItemRequest`, `@Valid` in `CartController` | Bean Validation at the boundary **plus** domain guards in `CartItem.requireValidQuantity`. Wrapper `Integer` + `@NotNull` to detect missing fields. |
| Error handling | `common/GlobalExceptionHandler`, `ErrorCode`, `ApiError` | One error shape; status mapping in one place; extending `ResponseEntityExceptionHandler`; not leaking stack traces; logging only unexpected errors. |
| Transactions | `CheckoutService.checkout` (`@Transactional`), `ProductService` / `OrderService` (`readOnly = true`) | Atomicity of checkout, rollback on `RuntimeException`, self-invocation/proxy pitfalls, why `readOnly`. Proven by the rollback integration test. |
| JPA mapping | `cart/Cart` ↔ `CartItem`, `order/Order` ↔ `OrderLine` | `mappedBy`, `cascade`, `orphanRemoval` (removing from the list deletes the row), `@OrderBy`, `@Enumerated(STRING)`, `updatable = false`. |
| Dirty checking | `CheckoutService`: `product.decreaseStock(...)` without `save` | Managed entities are flushed at commit. |
| N+1 queries | `CartRepository.findBySessionId`, `OrderRepository` (`@EntityGraph`) | Fetching children together with the parent; alternatives (JOIN FETCH, batch size). |
| Optimistic locking (preparation) | `Product.version` (`@Version`) | What Hibernate does with it, what exception appears, why it is not handled yet (Phase 3 exercise). |
| Schema migrations | `resources/db/migration/V1__…`, `V2__…`, `ddl-auto: validate` | Flyway vs `ddl-auto=update`, why no FK on `cart_item.product_id`. |
| Dependency injection | every service/controller | Constructor injection only, no field injection, easy unit testing without Spring. |
| Replaceable boundary | `session/SessionIdArgumentResolver` | One place to swap anonymous session for real auth; an abstraction with a concrete reason. |
| Testing strategy | `src/test/java/...` | Mockito unit tests for rules; Testcontainers + real PostgreSQL for persistence/API; context caching shares one container. |

## Frontend

| Topic | Where | What to talk about |
|---|---|---|
| React state | `App.tsx` (`cart`, `view`) | Lifting state to the closest common parent; discriminated union for the view; server response as the single source of truth. |
| useEffect for API sync | `App.tsx` (cart), `components/ProductList.tsx` (products) | Cleanup with `AbortController`, StrictMode double-mount in dev, ignoring aborted requests, loading/error states. |
| Local component state | `components/CartPanel.tsx` (`CartRow` draft quantity) | Controlled input as a draft; resetting it via `key` instead of syncing state in an effect. |
| Duplicate submit | `CartPanel.handleCheckout` | Disabled button **and** `useRef` guard; why state alone is not synchronous. Test: `CartPanel.test.tsx`. Backend-side idempotency is the real fix (Phase 2). |
| API error handling | `api/client.ts` | Typed `ApiError`, backend error body vs non-JSON error vs network failure, user-readable messages. |
| Session identity in the browser | `session.ts` | `crypto.randomUUID`, `localStorage` failure fallback, validating stored data. |
| TypeScript contracts | `api/types.ts` | Mirroring backend DTOs; nullable fields for missing products. |
| Dev proxy vs CORS | `vite.config.ts` | Why no CORS config is needed in dev; what changes when frontend and API are on different origins. |

## Planned topics (no code yet)

Listed only to show where they will attach. Nothing below is implemented.

| Topic | Planned phase / place |
|---|---|
| HTTP timeouts, retry, circuit breaker, fallback | Phase 2: `payment-service` integration |
| Idempotency keys, duplicate checkout/payment | Phase 2 |
| Race condition on the last item, optimistic lock conflict handling | Phase 3: checkout + `Product.version` |
| Thread pools, `CompletableFuture`, virtual threads, JMM | Phase 3 |
| Kafka, transactional outbox, consumer idempotency | Phase 4 |
| Authentication, authorization, sessions vs JWT, CORS/CSRF | Phase 5: replaces `SessionIdArgumentResolver` |
| Heap, GC, connection pool diagnostics | Phase 6 |
