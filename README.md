# Marketplace Interview Lab

A small, working marketplace used as a training ground for a Senior Fullstack (Java/Kotlin + React)
technical interview. It is **not** a portfolio clone of a real marketplace: every piece exists to give
concrete, runnable examples for interview topics (Stream API, money, transactions, JPA, REST, React state,
HTTP resilience, idempotency, distributed failures, concurrency, thread pools, virtual threads, …).

Current state: **Phase 3, concurrency lab** (on top of Phase 2, payment integration + resilience):

```text
Product list → Cart → Checkout → Order (PAYMENT_PENDING) → payment-service → PAID / PAYMENT_FAILED / PAYMENT_UNKNOWN
                                                                                   └─ reconciliation → PAID / PAYMENT_FAILED
```

Phase 3 made the production path safe under concurrency and added a separate training lab:

- **Production behaviour:** two shoppers buying the last unit → exactly one order, the other gets
  `409 CONCURRENT_STOCK_CHANGE` (optimistic locking on `Product.@Version`); concurrent edits of one cart are
  serialized by a cart row lock (no lost updates); a payment result arriving twice compensates stock/cart only once;
  no lock or transaction is held during remote calls.
- **Training experiments only** (`marketplace-service/src/test/java/pl/dch/marketplace/lab`, never part of the
  application): sequential vs `CompletableFuture` vs virtual threads, fixed thread pool queueing, and what virtual
  threads do not fix (downstream limits, DB pool, locks, CPU).

See [`docs/architecture.md`](docs/architecture.md) for the design (transaction boundaries, idempotency, retry,
circuit breaker, unknown results, concurrency strategy, the lab, and what is deliberately not built yet) and
[`docs/interview-map.md`](docs/interview-map.md) for where each interview topic lives in the code.

## Prerequisites

| Tool | Version used |
|---|---|
| JDK | 25 |
| Maven | 3.9+ |
| Docker (with Compose v2) | needed for PostgreSQL and for Testcontainers in the tests |
| Node.js | 20.19+ / 22.12+ (developed with 24) |

## Running locally

Compose runs only PostgreSQL. Both Spring Boot services are started from Maven (or the IDE), so they can be
restarted, debugged and hot-reloaded individually; putting them into Compose would make that workflow slower
without adding anything the tests do not already cover.

### 1. Start PostgreSQL

```bash
docker compose up -d
```

PostgreSQL 18 on `localhost:5432` (database/user/password: `marketplace`), data in the
`marketplace-postgres-data` volume. `docker compose down -v` wipes it.

### 2. Run payment-service (Kotlin, port 8081)

```bash
cd payment-service
mvn spring-boot:run
```

In-memory, no database. `PAYMENT_SLOW_DELAY` (default `5s`) controls the slow scenarios.

### 3. Run marketplace-service (Java, port 8080)

```bash
cd marketplace-service
mvn spring-boot:run
```

- API: http://localhost:8080/api/products
- Swagger UI: http://localhost:8080/swagger-ui.html (OpenAPI JSON: `/v3/api-docs`)

Flyway creates/migrates the schema and seeds six products. Overrides: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
`PAYMENT_SERVICE_URL` (default `http://localhost:8081`).

To try the payment failure scenarios, start it with scenario forwarding enabled (dev/test only):

```bash
PAYMENT_FORWARD_SCENARIO_HEADER=true mvn spring-boot:run
```

### 4. Run the frontend

```bash
cd marketplace-web
npm install
npm run dev
```

Open http://localhost:5173. The Vite dev server proxies `/api` to `localhost:8080`. In dev mode the cart shows a
"Payment scenario (dev)" select; it only has an effect when marketplace-service forwards the scenario header.

## Running tests

```bash
# marketplace-service: unit + integration tests (Testcontainers starts PostgreSQL, Docker must be running;
# payment-service is replaced by an in-process HTTP stand-in)
cd marketplace-service
mvn test

# only the Phase 3 race tests, or only the training lab ([LAB] lines show the observed timings)
mvn test -Dtest='pl.dch.marketplace.concurrency.*Test'
mvn test -Dtest='pl.dch.marketplace.lab.*Test'

# payment-service: unit tests + HTTP tests on a random port (no Docker needed)
cd payment-service
mvn test

# Frontend: unit/component tests and production build
cd marketplace-web
npm test
npm run build
```

## Trying the API by hand

Cart, checkout and order endpoints need an `X-Session-Id` header containing any UUID.
Checkout also needs an `Idempotency-Key` UUID; sending the same key again returns the same order.

```bash
S=$(uuidgen); K=$(uuidgen)
curl -s -H "X-Session-Id: $S" -H "Content-Type: application/json" \
     -d '{"productId":1,"quantity":2}' localhost:8080/api/cart/items
curl -s -X POST -H "X-Session-Id: $S" -H "Idempotency-Key: $K" localhost:8080/api/checkout   # 201, status PAID
curl -s -X POST -H "X-Session-Id: $S" -H "Idempotency-Key: $K" localhost:8080/api/checkout   # 200, same order
curl -s -H "X-Session-Id: $S" localhost:8080/api/orders
```

Payment scenarios (marketplace started with `PAYMENT_FORWARD_SCENARIO_HEADER=true`): add
`-H "X-Payment-Scenario: <scenario>"` to the checkout call.

| Scenario | Expected order status |
|---|---|
| `SUCCESS` | `PAID` |
| `DECLINED` | `PAYMENT_FAILED` / `DECLINED`, stock returned, items back in the cart |
| `SERVER_ERROR` | 3 attempts with the same payment key, then `PAYMENT_FAILED` / `NOT_PROCESSED` |
| `SERVER_ERROR_ONCE` | first attempt 503, retry succeeds → `PAID` |
| `SUCCESS_BUT_SLOW_RESPONSE` | `PAYMENT_UNKNOWN` after 2 s; payment exists → reconcile → `PAID` |
| `SLOW` | `PAYMENT_UNKNOWN`; reconcile at once → unchanged, reconcile after ~5 s → `PAID` |

```bash
curl -s -X POST -H "X-Session-Id: $S" localhost:8080/api/orders/<orderId>/reconcile-payment
curl -s localhost:8081/api/payments/by-idempotency-key/<paymentKey>
```

Several `SERVER_ERROR` checkouts in a row open the circuit breaker: for 15 s every checkout fails fast
(`NOT_PROCESSED`) without calling payment-service.

## Project structure

```text
.
├── README.md
├── docker-compose.yml          PostgreSQL for local development
├── docs/
│   ├── architecture.md         current architecture, decisions, what is not implemented
│   └── interview-map.md        interview topic → code location
├── marketplace-service/        Spring Boot 4 / Java 25 backend
│   └── src/main/java/pl/dch/marketplace/
│       ├── product/            catalog (entity, read API)
│       ├── cart/               anonymous cart (aggregate + API)
│       ├── checkout/           checkout orchestration, transactions 1 and 2, reconciliation
│       ├── order/              orders (payment state machine) and immutable order lines
│       ├── payment/            payment-service HTTP client: timeouts, retry, circuit breaker
│       ├── session/            X-Session-Id → SessionId (the one place to swap for real auth later)
│       └── common/             error codes and the global API error format
├── payment-service/            Spring Boot 4 / Kotlin simulated payment provider
│   └── src/main/kotlin/pl/dch/payment/
│       ├── payments/           Payment, idempotent PaymentService, in-memory repository
│       ├── simulation/         X-Payment-Scenario failure scenarios
│       └── api/                REST controller, DTOs, error handler
└── marketplace-web/            React 19 + TypeScript + Vite frontend
    └── src/
        ├── api/                typed fetch client and DTO types
        ├── components/         ProductList, CartPanel, OrderConfirmation, ErrorMessage
        ├── session.ts          anonymous session UUID in localStorage
        └── App.tsx             top-level state (cart, view, checkout attempt key)
```
