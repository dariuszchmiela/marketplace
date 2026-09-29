# Marketplace Interview Lab

A small, working marketplace used as a training ground for a Senior Fullstack (Java/Kotlin + React)
technical interview. It is **not** a portfolio clone of a real marketplace: every piece exists to give
concrete, runnable examples for interview topics (Stream API, money, transactions, JPA, REST, React state,
HTTP resilience, idempotency, distributed failures, concurrency, thread pools, virtual threads, Kafka,
transactional outbox, eventual consistency, authentication, sessions, CSRF, CORS, metrics, tracing, JVM diagnostics, …).

Current state: **Phase 6, observability + JVM / production diagnostics** (on top of Phase 5 security, Phase 4 Kafka +
outbox, Phase 3 concurrency lab and Phase 2 payment integration):

- **Metrics:** Actuator + Micrometer in all three services, Prometheus format: HTTP latency histograms (p50/p95/p99),
  JVM heap/GC/threads/CPU, HikariCP, Tomcat threads, Kafka client metrics (consumer lag), Resilience4j, plus deliberate
  business metrics (checkout outcomes and duration, payment client results, outbox backlog/lag, consumer
  processed/duplicate/retry/DLT, login/CSRF/token rejections). Bounded tags only: no ids, emails or tokens as labels.
- **Management security:** `/actuator/health` (+ liveness/readiness) public and status-only; metrics, Prometheus and the
  detailed `dependencies` health group need a management bearer token; env/heapdump/threaddump are not exposed.
- **Tracing:** Micrometer Tracing + OpenTelemetry, W3C `traceparent`: one trace from the HTTP request through
  payment-service, across the outbox gap (context stored in the outbox row) and Kafka into order-activity-service;
  trace/span ids in every log line.
- **Local stack** (`docker compose --profile observability up -d`): Prometheus, Grafana with a provisioned diagnostics
  dashboard, Jaeger.
- **Production/JVM lab** (training only, never in the application): pool exhaustion, slow downstream, executor
  saturation, lock contention and thread dumps, virtual threads, CPU-bound work, heap retention, GC and JFR, a load
  generator. See [`docs/production-diagnostics.md`](docs/production-diagnostics.md) (runbook, playbooks, measured results).

Phase 5 (still in place), security:

- **Users and login:** register/login/logout with email + password (BCrypt), Spring Security with a **server-side
  session** stored in PostgreSQL (Spring Session JDBC) and identified by the HttpOnly `MARKETPLACE_SESSION` cookie.
  One model only, no JWT (the trade-off is documented in `docs/architecture.md`).
- **Ownership:** carts and orders belong to the logged-in user; the owner key comes from the session, never from the
  browser (the old `X-Session-Id` header is gone). Another user's order is a 404.
- **CSRF** protection for every state-changing request (cookie `XSRF-TOKEN` → header `X-XSRF-TOKEN`), **CORS** only for
  configured origins, JSON 401/403 errors, default security headers.
- **Service-to-service:** marketplace → payment-service with a shared bearer token; order-activity-service's HTTP API
  (projection, dev simulation) behind its own internal token. Kafka stays unauthenticated local-dev infrastructure.

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

### Secrets and security settings

| Variable | Used by | Local default | Meaning |
|---|---|---|---|
| `PAYMENT_SERVICE_TOKEN` | marketplace-service, payment-service | `dev-only-payment-service-token` | shared bearer token marketplace → payment-service (same value in both) |
| `ORDER_ACTIVITY_API_TOKEN` | order-activity-service | `dev-only-order-activity-token` | bearer token for its internal HTTP API |
| `SESSION_COOKIE_SECURE` | marketplace-service | `true` | `Secure` flag of the session cookie; set `false` for plain-HTTP local development |
| `SESSION_TIMEOUT` | marketplace-service | `30m` | idle timeout of the server-side session |
| `APP_CORS_ALLOWED_ORIGINS` | marketplace-service | `http://localhost:5173` | exact origins allowed to call the API with cookies (comma-separated, never `*`) |
| `MANAGEMENT_TOKEN` | all three services | `dev-only-management-token` | bearer token for `/actuator/**` except the public health probes (Prometheus sends it) |
| `TRACING_EXPORT_ENABLED` | all three services | `false` | export spans over OTLP (to Jaeger from the observability profile); spans and propagation work without it |
| `OTLP_TRACING_ENDPOINT` | all three services | `http://localhost:4318/v1/traces` | OTLP/HTTP span endpoint |
| `TRACING_SAMPLING_PROBABILITY` | all three services | `1.0` | share of traces sampled (lab: all) |

The `dev-only-…` defaults exist only so that the lab starts without setup; the services log a warning when they are
used. Any shared or deployed environment must set real secrets. Production traffic is expected behind HTTPS (TLS
terminated at a load balancer or in the service), with `SESSION_COOKIE_SECURE=true`.

### 2. Run payment-service (Kotlin, port 8081)

```bash
cd payment-service
mvn spring-boot:run          # optionally PAYMENT_SERVICE_TOKEN=... (must match the marketplace)
```

In-memory, no database. `PAYMENT_SLOW_DELAY` (default `5s`) controls the slow scenarios. Every `/api/` call needs
`Authorization: Bearer <PAYMENT_SERVICE_TOKEN>`.

### 3. Run marketplace-service (Java, port 8080)

```bash
cd marketplace-service
SESSION_COOKIE_SECURE=false mvn spring-boot:run     # plain HTTP locally
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
Flyway history). `GET http://localhost:8082/api/order-activity/{orderId}` (with
`Authorization: Bearer <ORDER_ACTIVITY_API_TOKEN>`) shows the current status and the event history.

### 5. Run the frontend

```bash
cd marketplace-web
npm install
npm run dev
```

Open http://localhost:5173. The Vite dev server proxies `/api` to `localhost:8080` (same origin for the browser, so
the session and CSRF cookies just work). The catalog is visible without login; register or log in to use the cart.
A page reload keeps you logged in (the app asks `/api/auth/me`). In dev mode the cart shows a "Payment scenario (dev)"
select; it only has an effect when marketplace-service forwards the scenario header.

### 6. Optional: observability stack (Prometheus, Grafana, Jaeger)

```bash
docker compose --profile observability up -d   # Prometheus :9090, Grafana :3000, Jaeger :16686 (OTLP :4318)
# start the three services with TRACING_EXPORT_ENABLED=true to send spans to Jaeger
java tools/production-lab/LoadGenerator.java --scenario browse --concurrency 20 --requests 5000
```

- Grafana http://localhost:3000 (anonymous viewer, admin/admin): dashboard *Marketplace - production diagnostics*,
  provisioned from `observability/grafana/`.
- Prometheus http://localhost:9090/targets scrapes the three services on the host (`observability/prometheus/prometheus.yml`,
  with the local management token).
- Jaeger http://localhost:16686: one checkout = one trace across marketplace-service, payment-service and
  order-activity-service.
- Metrics by hand: `curl -H "Authorization: Bearer dev-only-management-token" localhost:8080/actuator/prometheus`.

The runbook (jcmd, JFR, thread/heap dumps, GC logs), troubleshooting playbooks and the measured experiments are in
[`docs/production-diagnostics.md`](docs/production-diagnostics.md).

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

# Phase 6 production/JVM lab: tagged production-lab, NOT part of `mvn test`; [PROD-LAB] lines show observations
mvn test -Pproduction-lab
mvn test -Pproduction-lab -Dtest=LockContentionLabTest -Dlab.hold=60s   # keeps the state for jcmd (PID printed)

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

curl plays the browser: a cookie jar keeps `MARKETPLACE_SESSION` and `XSRF-TOKEN`, and every POST/PUT/DELETE copies
the CSRF cookie into the `X-XSRF-TOKEN` header. Checkout also needs an `Idempotency-Key` UUID; sending the same key
again returns the same order. (Marketplace started with `SESSION_COOKIE_SECURE=false` for plain HTTP.)

```bash
J=/tmp/jar.txt; rm -f $J; K=$(uuidgen)
csrf() { curl -s -o /dev/null -b $J -c $J localhost:8080/api/auth/csrf; awk '$6=="XSRF-TOKEN"{print $7}' $J; }
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $(csrf)" -H "Content-Type: application/json" \
     -d '{"email":"me@example.com","password":"correct horse battery"}' localhost:8080/api/auth/register
T=$(csrf)   # login/registration replaces the CSRF token
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" -H "Content-Type: application/json" \
     -d '{"productId":1,"quantity":2}' localhost:8080/api/cart/items
curl -s -b $J -c $J -X POST -H "X-XSRF-TOKEN: $T" -H "Idempotency-Key: $K" localhost:8080/api/checkout   # 201, PAID
curl -s -b $J -c $J -X POST -H "X-XSRF-TOKEN: $T" -H "Idempotency-Key: $K" localhost:8080/api/checkout   # 200, same order
curl -s -b $J localhost:8080/api/orders
curl -s -b $J -c $J -X POST -H "X-XSRF-TOKEN: $T" localhost:8080/api/auth/logout                         # 204
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
curl -s -b $J -X POST -H "X-XSRF-TOKEN: $T" localhost:8080/api/orders/<orderId>/reconcile-payment
curl -s -H "Authorization: Bearer dev-only-payment-service-token" localhost:8081/api/payments/by-idempotency-key/<paymentKey>
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
curl -s -H "Authorization: Bearer dev-only-order-activity-token" localhost:8082/api/order-activity/<orderId>

# dead-letter topic
docker exec marketplace-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic marketplace.order-events.DLT --from-beginning --property print.key=true --property print.headers=true
```

Failure demos (order-activity-service started with `ORDER_ACTIVITY_SIMULATION_ENABLED=true`):

```bash
# the next OrderPaid fails twice (transient) -> retried -> processed
curl -X POST -H "Authorization: Bearer dev-only-order-activity-token" -H "Content-Type: application/json" -d '{"match":"OrderPaid","mode":"TRANSIENT","times":2}' \
  localhost:8082/api/simulation/failures
# the next OrderPaid fails permanently -> dead-letter topic
curl -X POST -H "Authorization: Bearer dev-only-order-activity-token" -H "Content-Type: application/json" -d '{"match":"OrderPaid","mode":"PERMANENT"}' \
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
├── docker-compose.yml          PostgreSQL + Kafka (KRaft); profile "observability": Prometheus, Grafana, Jaeger
├── docs/
│   ├── architecture.md         current architecture, decisions, what is not implemented
│   ├── production-diagnostics.md  metrics, tracing, health, jcmd/JFR runbook, playbooks, lab results
│   └── interview-map.md        interview topic → code location
├── observability/              Prometheus scrape config, Grafana datasources + dashboard (as code)
├── tools/production-lab/       LoadGenerator.java (HTTP load, p50/p95/p99), MemoryDemo.java (GC log, OOM, jcmd target)
├── marketplace-service/        Spring Boot 4 / Java 25 backend
│   └── src/main/java/pl/dch/marketplace/
│       ├── product/            catalog (entity, read API)
│       ├── auth/               users, login/register/logout, Spring Security config, CSRF/CORS, JSON 401/403
│       ├── checkout/           checkout orchestration, transactions 1 and 2, reconciliation
│       ├── order/              orders (payment state machine), immutable order lines, order events
│       ├── outbox/             transactional outbox: writer, JDBC repository, polling publisher, Kafka sender
│       ├── payment/            payment-service HTTP client: timeouts, retry, circuit breaker
│       ├── cart/               cart of the logged-in user (aggregate + API)
│       ├── session/            SessionId (owner key) resolved from the authenticated principal
│       ├── observability/      management endpoint security (token), security metrics
│       └── common/             error codes and the global API error format
├── payment-service/            Spring Boot 4 / Kotlin simulated payment provider
│   └── src/main/kotlin/pl/dch/payment/
│       ├── payments/           Payment, idempotent PaymentService, in-memory repository
│       ├── simulation/         X-Payment-Scenario failure scenarios
│       ├── observability/      business/security metrics, management token filter
│       └── api/                REST controller, DTOs, error handler
├── order-activity-service/     Spring Boot 4 / Java 25 Kafka consumer (idempotent projection, retry, DLT)
│   └── src/main/java/pl/dch/orderactivity/
│       ├── event/              own copy of the event contract, parser/validation, permanent vs transient failures
│       ├── activity/           projector (one local transaction), processed_event + order_activity repositories
│       ├── kafka/              listener, retry / dead-letter configuration
│       ├── simulation/         deterministic failure injection (dev/test only)
│       ├── observability/      consumer metrics, passive consumer health, management token filter
│       └── api/                read-only projection API
└── marketplace-web/            React 19 + TypeScript + Vite frontend
    └── src/
        ├── api/                typed fetch client (cookies, CSRF header, 401 handling) and DTO types
        ├── components/         ProductList, CartPanel, OrderConfirmation, ErrorMessage
        ├── components/AuthPanel.tsx  login / create account
        └── App.tsx             top-level state (cart, view, checkout attempt key)
```
