# Interview map

Where each interview topic can be demonstrated in the **current** code (Phases 1–5).
Topics for later phases are listed at the bottom as planned only. They have no code yet.

Paths are relative to `marketplace-service/src/main/java/pl/dch/marketplace/` (backend),
`payment-service/src/main/kotlin/pl/dch/payment/` (Kotlin service), `order-activity-service/src/main/java/pl/dch/orderactivity/`
(consumer)
and `marketplace-web/src/` (frontend) unless stated otherwise. Test-only paths (`concurrency/`, `lab/`) are under
`marketplace-service/src/test/java/pl/dch/marketplace/`.

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
| Schema migrations | `resources/db/migration/V1__…`, `V2__…`, `V3__payment_integration.sql`, `ddl-auto: validate` | Flyway vs `ddl-auto=update`, never editing applied migrations, unique and `CHECK` constraints as the last line of defence, why no FK on `cart_item.product_id`. |
| Dependency injection | every service/controller | Constructor injection only, no field injection, easy unit testing without Spring. |
| Replaceable boundary | `session/SessionIdArgumentResolver` | Built in Phase 1 as the one place to swap the anonymous header for real auth — in Phase 5 only this class changed (principal instead of header); an abstraction that paid off. |
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

## Concurrency — production code (Phase 3)

| Topic | Where | What to talk about |
|---|---|---|
| Race condition: last unit | test `concurrency/LastItemCheckoutConcurrencyIntegrationTest` (two sessions, stock 1) | Read–check–write race; why two sequential calls prove nothing; how the test forces the real interleaving (`RowLockHolder` + `pg_stat_activity` lock waiters) without sleeps in production code. |
| Optimistic locking / `@Version` | `product/Product.version`, `Product.decreaseStock`, `checkout/OrderPlacementService` | `UPDATE … WHERE version = ?`, 0 rows → `StaleObjectStateException` → `ObjectOptimisticLockingFailureException` at commit; no lost update, no oversell; false conflicts on hot rows (measured in the stress test). |
| 409 conflict translation | `checkout/CheckoutService.translate` (`CONCURRENT_STOCK_CHANGE`), `common/GlobalExceptionHandler.handleConcurrencyFailure` (`CONCURRENT_MODIFICATION`), test `common/ConcurrencyErrorMappingTest` | Expected races are business results (409, "refresh and try again"), not 500; translate where the context is known, keep a central safety net; never leak JPA/SQL text. |
| Atomic update vs entity update | `product/ProductRepository.increaseStock`, test `concurrency/StockUpdateConcurrencyIntegrationTest.compensationDuringAPurchaseIsNotOverwritten` | Why the compensation must never fail; why it still bumps the version (otherwise the stale purchase overwrites the returned units — mutation-checked). Bulk JPQL bypasses the persistence context. |
| Pessimistic locking, only where appropriate | `cart/CartRepository.findBySessionIdForUpdate` / `lockOrCreate`, `order/OrderRepository.findByIdForUpdate` | One owner, low contention, failing is worse than waiting → `SELECT … FOR UPDATE`; `INSERT … ON CONFLICT DO NOTHING` for race-free creation; no fetch join with `FOR UPDATE` (outer join). Tests: `CartConcurrencyIntegrationTest` (lost update 2 vs 11 before the fix). |
| Idempotent compensation under concurrency | `checkout/OrderPaymentUpdater.applyOutcome` / `returnItems`; test `concurrency/OrderPaymentRaceIntegrationTest` | Row lock + final-state check = exactly-once effect of an at-least-once delivered result; checkout result vs reconciliation arriving together. |
| Deadlock avoidance | lock order order → cart → products (ascending id), `hibernate.order_updates`, sorted lines in `returnItems` | Consistent lock ordering; what PostgreSQL does on a deadlock (one victim, mapped to 409 by the safety net). |
| Locks never held across remote calls | `payment/PaymentClient.requireNoTransaction`; test `OrderPaymentRaceIntegrationTest.noRowLockOrTransactionIsHeldWhilePaymentServiceIsCalled` (`FOR UPDATE NOWAIT` probe) | Short critical sections; the checkout's three-step structure under concurrency. |

## Concurrency lab — training experiments only (Phase 3)

Test sources only (`lab/`), never part of the application. Observed timings are printed as `[LAB]` lines.

| Topic | Where | What to talk about |
|---|---|---|
| Blocking I/O | `lab/DownstreamClient.get` (`HttpClient.send`), `lab/FakeDownstream` | The calling thread is parked while waiting; which thread that is decides everything below. |
| Parallel independent calls | `lab/ProductPageLoader`: `loadSequentially` / `loadWithCompletableFuture` / `loadWithVirtualThreads`; `ParallelCallsLabTest` | Sum vs max of latencies (≈ 900 vs ≈ 300 ms); only *independent* calls can be parallelized (checkout's steps cannot). |
| `CompletableFuture` + explicit executor | `ProductPageLoader.newIoExecutor`, `loadWithCompletableFuture` | `supplyAsync(…, executor)` instead of the common pool (sized for CPU, shared JVM-wide); `allOf` waits for all; fail-fast wiring with `anyOf`; `orTimeout`; one `join()` at the edge; `cancel` does not interrupt. |
| Virtual threads | `loadWithVirtualThreads`, `Executors.newVirtualThreadPerTaskExecutor()` | Plain blocking code, cheap waiting; try-with-resources executor = structured lifetime; `StructuredTaskScope` (preview in Java 25) for fail-fast. |
| Fixed thread pool, task queueing | `lab/ThreadPoolLabTest` | Pool of 4, 20 tasks: max 4 running, 16 queued, 5 batches (≈ 1000 ms); queue growth = latency growth. |
| Downstream limits | `lab/VirtualThreadLimitsLabTest.fiftyVirtualThreads…` | 50 in flight, 5 processed (≈ 2000 ms): capacity is set by the downstream, not by the number of threads. |
| Why virtual threads do not solve every bottleneck | `VirtualThreadLimitsLabTest` (lock, CPU), `lab/DatabasePoolLimitLabTest` (HikariCP 10) | They remove the thread-per-request cost of *waiting*; they do not add DB/HTTP connections, rate limits, lock throughput or CPU. |

## Events, outbox and Kafka (Phase 4)

Marketplace paths are under `marketplace-service/src/main/java/pl/dch/marketplace/`, consumer paths under
`order-activity-service/src/main/java/pl/dch/orderactivity/`.

| Topic | Where | What to talk about |
|---|---|---|
| Why a DB + Kafka dual write is unsafe | `checkout/OrderPlacementService.placeOrder`, `checkout/OrderPaymentUpdater.applyOutcome` (they write outbox rows, never Kafka) | Send before commit → event for a rolled-back order; send after commit → lost event on crash. No atomic commit across two systems without XA. |
| Transactional outbox | `outbox/OutboxWriter` (`Propagation.MANDATORY`), `order/events/OrderEvents`, `db/migration/V4__transactional_outbox.sql`; test `outbox/OrderOutboxEventsIntegrationTest` (rollback, same commit, no duplicates on replay/final state) | Event row in the same transaction as the state change; JDBC joining the JPA transaction; what "same transaction" buys and what it does not. |
| Outbox table and polling | `outbox/OutboxRepository.claimBatch`, `outbox/OutboxPublisher`, `outbox/OutboxConfiguration.OutboxPollingScheduler` | Bounded batches, partial index on unpublished rows, backoff via `next_attempt_at`, stopping a batch on failure; polling vs CDC. |
| Concurrent publishers: `SKIP LOCKED` | `OutboxRepository.claimBatch`; tests `OutboxPublisherIntegrationTest.twoPublisherWorkersNeverClaimTheSameRow`, `aSecondWorkerCannotOvertakeTheHeadEventOfAnAggregateHeldByTheFirst` | Parallel workers without a global lock; why plain `SKIP LOCKED` breaks per-order ordering and how "head of line per aggregate" fixes it. |
| Crash after publish, before mark | test `OutboxPublisherIntegrationTest.crashAfterSendBeforeMarkPublishesTheSameEventTwice`; consumer test `theSameEventDeliveredTwiceIsAppliedOnce` | The core lesson: outbox = at-least-once. Two records, one `eventId`; only consumer idempotency makes it harmless. |
| Kafka unavailable | test `OutboxPublisherIntegrationTest.kafkaUnavailableLeavesRowsPendingAndALaterPollPublishesThem`; `outbox.publish_failed` log | Business commit unaffected, rows pending with attempts/error, backoff, later publication; `max.block.ms`. |
| Kafka producer | `outbox/KafkaEventSender`, `spring.kafka.producer` in `application.yaml` | `acks=all`, idempotent producer (what it dedups and what it does not), bounded retries via `delivery.timeout.ms`, synchronous ack wait, headers. |
| Key = orderId, ordering per partition | `KafkaEventSender` (key = aggregate id); test `publishesPendingEventsKeyedByOrderIdInOrderWithHeaders`; consumer test `eventsOfOneOrderAreAppliedInPartitionOrder` | Order only within a partition; parallelism across orders; repartitioning remaps keys; cross-topic workflows have no order. |
| Event contract / serialization | `outbox/EventEnvelope`, `order/events/OrderEvents` (payload records); consumer `event/OrderEventParser`; tests `OutboxWriterTest`, `OrderEventParserTest` | Envelope vs payload, `schemaVersion`, additive changes, tolerant reader, no entities on the wire, no bearer session id in events, JSON numbers and `BigDecimal` scale. |
| Idempotent consumer | `activity/ProcessedEventRepository.markProcessed` (`ON CONFLICT DO NOTHING`), `activity/OrderActivityProjector.apply` | Dedup by business event id in the same local transaction as the effect; why offsets are not enough; concurrent duplicates wait on the primary key. |
| Consumer transaction boundary / offsets | `OrderActivityProjector.apply` (`@Transactional`), `kafka/OrderEventListener`, `spring.kafka.listener.ack-mode: record`, `enable-auto-commit: false` | DB commit first, offset commit after; the gap is covered by idempotency; no XA, no Kafka transactions. Test `transientFailureIsRetriedAndTheFailedAttemptsLeaveNoTrace`. |
| Retry: transient vs permanent | `kafka/KafkaConsumerConfiguration` (`DefaultErrorHandler`, `ExponentialBackOffWithMaxRetries`, `addNotRetryableExceptions`), `event/PermanentEventException` | Bounded retries with backoff; never retry what cannot succeed (malformed, unsupported version, impossible transition); in-place retry blocks the partition. |
| Dead-letter topic | `DeadLetterPublishingRecoverer` in `KafkaConsumerConfiguration`; tests `exhaustedRetries…`, `malformedEventIsDeadLetteredWithoutRetries`, `unsupportedSchemaVersionIsDeadLettered` | DLT headers (original topic/partition/offset, exception cause), no stack traces, the partition continues; what to do with DLT records. |
| Validating state in the consumer | `OrderActivityProjector` (`ALLOWED_TRANSITIONS`, `sequence`); tests `impossibleTransitionIsDeadLettered…`, `replayedOlderEventIsIgnored` | Partition order is not a business guarantee: replays, manual messages, migrations. |
| Eventual consistency | tests `OutboxSchedulingIntegrationTest`, `OrderActivityConsumerIntegrationTest.eventuallyBuildsTheProjectionFromOrderCreated` (Awaitility); `api/OrderActivityController` | Write completes without waiting for Kafka/consumer; the read model lags (404 = not seen yet); bounded waiting in tests instead of sleeps. |
| Deterministic failure injection | consumer `simulation/FailureSimulator` (+ `SimulationController`, dev only); broken publishers built from real parts in `OutboxPublisherIntegrationTest` | Test hooks outside business logic; failing after the writes proves the rollback. |

## Security (Phase 5)

Marketplace paths under `marketplace-service/src/main/java/pl/dch/marketplace/`; tests under
`marketplace-service/src/test/java/pl/dch/marketplace/auth/`.

| Topic | Where | What to talk about |
|---|---|---|
| Authentication vs authorization | `auth/SecurityConfiguration.apiSecurity` (who must be logged in), `session/SessionIdArgumentResolver` + owner-scoped repository queries (what they may access) | 401 = unknown caller, 403 = known but not allowed (or CSRF), 404 for someone else's order (don't leak existence). Tests: `AuthorizationIntegrationTest`. |
| Password hashing | `auth/UserRegistrationService`, `SecurityConfiguration.passwordEncoder` (BCrypt), `auth/AppUserDetailsService` + `DaoAuthenticationProvider` | Salted slow hash, `matches` instead of comparing hashes, 72-byte BCrypt limit, dummy hash for unknown users (timing), same 401 for unknown email and wrong password. Tests: `AuthenticationIntegrationTest`. |
| Server-side session | `auth/AuthController.establishSession`, `auth/AuthenticatedUser` (serializable principal, `getName()` = user id) | What is in the session and what is not (no hash, no email as principal name); no DB lookup per request; revocation = delete. |
| JDBC Spring Session | `db/migration/V6__spring_session.sql`, `spring.session.*` in `application.yaml`; `SessionLifecycleIntegrationTest` | Sessions shared by all instances, Flyway-owned schema, timeout, expiry cleanup; sticky sessions not needed. |
| Secure cookie | `SecurityConfiguration.sessionCookieSerializer`, `app.security.session-cookie` | HttpOnly, SameSite=Lax, Secure (configurable for local HTTP), Path; why the explicit bean was needed (the default was a cookie named `SESSION` without our settings — found by a test). |
| Session fixation | `SecurityConfiguration.sessionAuthenticationStrategy` (`ChangeSessionIdAuthenticationStrategy`); tests `loginReplacesTheSessionIdSessionFixationProtection`, `aSessionIdChosenByAnAttackerIsNeverAdopted` | Attacker plants/knows a session id before login; a new id after login makes it useless. |
| Logout and expiry | `AuthController.logout` (`SecurityContextLogoutHandler`, `CsrfLogoutHandler`); tests `logoutInvalidates…`, `anExpiredSessionIsRejected` | Server-side invalidation vs "delete the JWT in the browser". |
| CSRF — why it matters with cookies | `SecurityConfiguration.apiSecurity` (`csrf.spa()` + `CookieCsrfTokenRepository`), `auth/AuthController.csrf`; `CsrfIntegrationTest` | Cookies are sent automatically, also on forged cross-site requests; double-submit token (cookie → header); login CSRF; why stateless double-submit relies on attackers not writing our cookies (cookie tossing, `__Host-`). |
| Why bearer-header APIs differ | `payment/PaymentClientConfiguration` (service token header), payment-service `security/ServiceTokenFilter.kt` | Headers are not attached by the browser → no CSRF; the risk moves to token theft/storage. |
| CORS and the same-origin policy | `SecurityConfiguration.corsConfigurationSource`, `AppSecurityProperties.Cors` (rejects `*`); `CorsAndHeadersIntegrationTest`; `marketplace-web/vite.config.ts` (proxy) | SOP restricts reading responses; CORS relaxes it for listed origins; credentials forbid `*`; CORS ≠ CSRF protection. |
| User resource isolation | `SessionIdArgumentResolver` (no header fallback), `OrderRepository.findByIdAndSessionId`, `CartRepository.findBySessionId*`; tests `cartsAreIsolatedBetweenUsers`, `anotherUsersOrderIsNotFoundRatherThanForbidden`, `theLegacyXSessionIdHeaderGrantsNothing`, `theSameIdempotencyKeyOfTwoUsersCreatesTwoIndependentOrders` | Never trust a client-supplied owner id (IDOR); idempotency keys scoped per owner. |
| Security error model | `auth/SecurityErrorHandler` (entry point + access denied handler), `common/ErrorCode`, `GlobalExceptionHandler.statusFor` | JSON instead of HTML/redirects; separate `CSRF_FAILED` so clients can recover. |
| Service-to-service authentication | payment-service `security/ServiceTokenFilter.kt` (`MessageDigest.isEqual`), order-activity `security/InternalApiTokenFilter`; `PaymentClientTest.everyPaymentAndReconciliationCallCarriesTheServiceToken`, `ServiceTokenIntegrationTest`, `InternalApiSecurityIntegrationTest` | Shared secret: simple but no per-caller identity, manual rotation, needs TLS; alternatives mTLS, workload identity, OAuth2 client credentials. Separate secrets per boundary. |
| JWT vs server-side session | `docs/architecture.md` → Security | Revocation, storage (XSS vs CSRF), scaling, where each fits; why a second auth mode was not added. |
| Secrets and logging | `PaymentClientProperties.toString`, Kotlin `ServiceSecurityProperties.toString`, `CredentialsRequest.toString`, `auth.*`/`security.*` log lines; `OutputCaptureExtension` assertions | Never log passwords, hashes, cookies, CSRF or service tokens; user id instead of email. |
| Kafka security boundary | `docker-compose.yml` (PLAINTEXT), `docs/architecture.md` → Kafka security boundary | Not implemented locally on purpose; production: TLS + SASL/workload identity + ACLs per service (produce vs consume vs DLT). |

## Frontend

| Topic | Where | What to talk about |
|---|---|---|
| React state | `App.tsx` (`cart`, `view`) | Lifting state to the closest common parent; discriminated union for the view; server response as the single source of truth. |
| useEffect for API sync | `App.tsx` (cart), `components/ProductList.tsx` (products) | Cleanup with `AbortController`, StrictMode double-mount in dev, ignoring aborted requests, loading/error states. |
| Local component state | `components/CartPanel.tsx` (`CartRow` draft quantity) | Controlled input as a draft; stable `key` (product id) plus adjusting state during render when the server quantity changes, instead of a remount or an effect. |
| Duplicate submit | `CartPanel.handleCheckout`, `ProductList.handleAdd` | Disabled button **and** `useRef` guard; why state alone is not synchronous. Tests: `CartPanel.test.tsx`, `App.test.tsx` (one request per double click). |
| Idempotency key per attempt | `App.tsx` (`checkoutAttemptKey` ref, `mayHaveBeenProcessed`) | New key per attempt, same key when retrying after a network error/5xx, new key after a 4xx; why a `useRef`, not state. Test: `App.test.tsx`. |
| Handling a concurrency conflict | `App.tsx` (`STOCK_CHANGED_CODES`, `catalogVersion` key remounting `ProductList`) | 409 `CONCURRENT_STOCK_CHANGE`: show the message, reload cart and catalog, retry as a new attempt (definitive rejection) — unlike ambiguous 5xx. |
| Rendering async results | `components/OrderConfirmation.tsx` | Paid / declined / technical failure / "Payment status is being verified." + reconciliation button; exhaustive `switch` over a string union. |
| API error handling | `api/client.ts` | Typed `ApiError`, backend error body vs non-JSON error vs network failure, user-readable messages. |
| Auth state in the browser | `App.tsx` (`auth` state, `/api/auth/me` on start, `onUnauthorized`), `components/AuthPanel.tsx` | Nothing in `localStorage`: the HttpOnly cookie is invisible to JS; restoring a session = asking the server; clearing the password from state; 401 → logged-out view. Tests: `App.test.tsx`. |
| Centralized CSRF handling | `api/client.ts` (`readCsrfToken`, `refreshCsrfToken`, `request`) | Copy cookie → header for unsafe methods only, fetch token when missing, one retry on `CSRF_FAILED`, `credentials: 'include'`. Tests: `api/client.test.ts`. |
| TypeScript contracts | `api/types.ts` | Mirroring backend DTOs; nullable fields for missing products. |
| Dev proxy vs CORS | `vite.config.ts` | Why no CORS config is needed in dev; what changes when frontend and API are on different origins. |

## Planned topics (no code yet)

Listed only to show where they will attach. Nothing below is implemented.

| Topic | Planned phase / place |
|---|---|
| Scheduled reconciliation, expiry of never-found payments | later (see architecture.md, "Not implemented yet") |
| Server-side retry of optimistic conflicts, conditional atomic stock update, stock reservation | not planned yet (alternatives documented in architecture.md) |
| JMM details (happens-before, `volatile`, safe publication) beyond what the lab uses | later |
| Kafka-based payment commands, sagas, CDC / Debezium, Schema Registry | not planned yet |
| Heap, GC, connection pool diagnostics | Phase 6 |
