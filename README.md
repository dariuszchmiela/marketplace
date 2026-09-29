# Marketplace Interview Lab

A small, working marketplace used as a training ground for a Senior Fullstack (Java/Kotlin + React)
technical interview. It is **not** a portfolio clone of a real marketplace: every piece exists to give
concrete, runnable examples for interview topics (Stream API, money, transactions, JPA, REST, React state,
HTTP resilience, idempotency, distributed failures, concurrency, thread pools, virtual threads, Kafka,
transactional outbox, eventual consistency, …).

Current state: **Phase 4, Kafka + transactional outbox + eventual consistency** (on top of Phase 3, concurrency lab,
and Phase 2, payment integration + resilience):

```text
Product list → Cart → Checkout → Order (PAYMENT_PENDING) → payment-service → PAID / PAYMENT_FAILED / PAYMENT_UNKNOWN
                                                                                   └─ reconciliation → PAID / PAYMENT_FAILED
```

Phase 4 adds an asynchronous, event-driven path next to the unchanged synchronous API:

```text
order transaction ── orders + outbox_event (same commit)
                          │  outbox publisher (polling, FOR UPDATE SKIP LOCKED)
                          ▼
                    Kafka marketplace.order-events (key = orderId)
                          │
                          ▼
        order-activity-service ── processed_event + order_activity (same commit) ── failures → .DLT
```

The marketplace never waits for Kafka or the consumer: the projection catches up later (eventual consistency).
Delivery is at-least-once; the consumer deduplicates by `eventId`.

Phase 3 (still in place) made the production path safe under concurrency and added a separate training lab:

- **Production behaviour:** two shoppers buying the last unit → exactly one order, the other gets
  `409 CONCURRENT_STOCK_CHANGE` (optimistic locking on `Product.@Version`); concurrent edits of one cart are
  serialized by a cart row lock (no lost updates); a payment result arriving twice compensates stock/cart only once;
  no lock or transaction is held during remote calls.
- **Training experiments only** (`marketplace-service/src/test/java/pl/dch/marketplace/lab`, never part of the
  application): sequential vs `CompletableFuture` vs virtual threads, fixed thread pool queueing, and what virtual
  threads do not fix (downstream limits, DB pool, locks, CPU).

See [`docs/architecture.md`](docs/architecture.md) for the design (transaction boundaries, idempotency, retry,
circuit breaker, unknown results, concurrency strategy, the lab, outbox/Kafka/consumer, and what is deliberately not
built yet) and
[`docs/interview-map.md`](docs/interview-map.md) for where each interview topic lives in the code.

## Prerequisites

| Tool | Version used |
|---|---|
| JDK | 25 |
| Maven | 3.9+ |
| Docker (with Compose v2) | needed for PostgreSQL, Kafka and for Testcontainers in the tests |
| Node.js | 20.19+ / 22.12+ (developed with 24) |

## Running locally

Compose runs the infrastructure only (PostgreSQL, Kafka). The three Spring Boot services are started from Maven (or
the IDE), so they can be restarted, debugged and hot-reloaded individually; putting them into Compose would make that
workflow slower without adding anything the tests do not already cover.

### 1. Start PostgreSQL and Kafka

```bash
docker compose up -d
docker compose ps          # wait until both are "healthy"
```

- PostgreSQL 18 on `localhost:5432` (database/user/password: `marketplace`), data in the `marketplace-postgres-data`
  volume. `docker compose down -v` wipes it.
- Kafka 4.1 (`apache/kafka`, single node, KRaft — no ZooKeeper) on `localhost:9092`. Topic auto-creation is off; the
  services create `marketplace.order-events` and `marketplace.order-events.DLT` (3 partitions each) at startup.

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
`PAYMENT_SERVICE_URL` (default `http://localhost:8081`), `KAFKA_BOOTSTRAP_SERVERS` (default `localhost:9092`),
`ORDER_EVENTS_TOPIC`, `OUTBOX_PUBLISHER_ENABLED` (default `true`). Order events are written to the outbox and published
to Kafka every 500 ms; if Kafka is down, checkout still works and the events are published once it is back.

To try the payment failure scenarios, start it with scenario forwarding enabled (dev/test only):

```bash
PAYMENT_FORWARD_SCENARIO_HEADER=true mvn spring-boot:run
```

### 4. Run order-activity-service (Java, port 8082) — the Kafka consumer

```bash
cd order-activity-service
mvn spring-boot:run
# with deterministic failure simulation for demos (dev only):
ORDER_ACTIVITY_SIMULATION_ENABLED=true mvn spring-boot:run
```

Consumes `marketplace.order-events` and keeps its own projection in the `order_activity` schema (own tables, own
Flyway history). `GET http://localhost:8082/api/order-activity/{orderId}` shows the current status and the event history.

### 5. Run the frontend

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

# only the Phase 4 outbox / Kafka tests (real Kafka via Testcontainers)
mvn test -Dtest='pl.dch.marketplace.outbox.*Test'

# payment-service: unit tests + HTTP tests on a random port (no Docker needed)
cd payment-service
mvn test

# order-activity-service: consumer tests against real Kafka + PostgreSQL (Testcontainers, Docker must be running)
cd order-activity-service
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

### Watching the events

```bash
# outbox rows of an order (published_at stays empty until the publisher sent them)
docker exec marketplace-postgres psql -U marketplace -At -c \
  "select sequence, event_type, published_at, attempt_count, last_error from outbox_event where aggregate_id = '<orderId>'"

# the topic (key, partition, offset, headers, value)
docker exec marketplace-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic marketplace.order-events --from-beginning --property print.key=true --property print.partition=true \
  --property print.offset=true --property print.headers=true

# the consumer's view (eventually consistent)
curl -s localhost:8082/api/order-activity/<orderId>

# dead-letter topic
docker exec marketplace-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic marketplace.order-events.DLT --from-beginning --property print.key=true --property print.headers=true
```

Failure demos (order-activity-service started with `ORDER_ACTIVITY_SIMULATION_ENABLED=true`):

```bash
# the next OrderPaid fails twice (transient) -> retried -> processed
curl -X POST -H "Content-Type: application/json" -d '{"match":"OrderPaid","mode":"TRANSIENT","times":2}' \
  localhost:8082/api/simulation/failures
# the next OrderPaid fails permanently -> dead-letter topic
curl -X POST -H "Content-Type: application/json" -d '{"match":"OrderPaid","mode":"PERMANENT"}' \
  localhost:8082/api/simulation/failures
# replay an already published event (= crash after send, before mark): the consumer ignores the duplicate
docker exec marketplace-postgres psql -U marketplace -c \
  "update outbox_event set published_at = null where aggregate_id = '<orderId>' and sequence = 1"
# Kafka outage: stop it, check out, see the outbox rows stay pending, start it, see them published
docker stop marketplace-kafka
docker start marketplace-kafka
```

On Windows Git Bash, prefix `docker exec … /opt/kafka/…` commands with `MSYS_NO_PATHCONV=1`.

## Project structure

```text
.
├── README.md
├── docker-compose.yml          PostgreSQL + Kafka (KRaft) for local development
├── docs/
│   ├── architecture.md         current architecture, decisions, what is not implemented
│   └── interview-map.md        interview topic → code location
├── marketplace-service/        Spring Boot 4 / Java 25 backend
│   └── src/main/java/pl/dch/marketplace/
│       ├── product/            catalog (entity, read API)
│       ├── cart/               anonymous cart (aggregate + API)
│       ├── checkout/           checkout orchestration, transactions 1 and 2, reconciliation
│       ├── order/              orders (payment state machine), immutable order lines, order events
│       ├── outbox/             transactional outbox: writer, JDBC repository, polling publisher, Kafka sender
│       ├── payment/            payment-service HTTP client: timeouts, retry, circuit breaker
│       ├── session/            X-Session-Id → SessionId (the one place to swap for real auth later)
│       └── common/             error codes and the global API error format
├── payment-service/            Spring Boot 4 / Kotlin simulated payment provider
│   └── src/main/kotlin/pl/dch/payment/
│       ├── payments/           Payment, idempotent PaymentService, in-memory repository
│       ├── simulation/         X-Payment-Scenario failure scenarios
│       └── api/                REST controller, DTOs, error handler
├── order-activity-service/     Spring Boot 4 / Java 25 Kafka consumer (idempotent projection, retry, DLT)
│   └── src/main/java/pl/dch/orderactivity/
│       ├── event/              own copy of the event contract, parser/validation, permanent vs transient failures
│       ├── activity/           projector (one local transaction), processed_event + order_activity repositories
│       ├── kafka/              listener, retry / dead-letter configuration
│       ├── simulation/         deterministic failure injection (dev/test only)
│       └── api/                read-only projection API
└── marketplace-web/            React 19 + TypeScript + Vite frontend
    └── src/
        ├── api/                typed fetch client and DTO types
        ├── components/         ProductList, CartPanel, OrderConfirmation, ErrorMessage
        ├── session.ts          anonymous session UUID in localStorage
        └── App.tsx             top-level state (cart, view, checkout attempt key)
```
