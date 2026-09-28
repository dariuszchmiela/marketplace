# Marketplace Interview Lab

A small, working marketplace used as a training ground for a Senior Fullstack (Java/Kotlin + React)
technical interview. It is **not** a portfolio clone of a real marketplace: every piece exists to give
concrete, runnable examples for interview topics (Stream API, money, transactions, JPA, REST, React state, …).

Current state: **Phase 1, the core vertical slice**:

```text
Product list → Add to cart → View/update cart → Checkout → Order stored in PostgreSQL → Order confirmation
```

See [`docs/architecture.md`](docs/architecture.md) for the design (and what is deliberately not built yet)
and [`docs/interview-map.md`](docs/interview-map.md) for where each interview topic lives in the code.

## Prerequisites

| Tool | Version used |
|---|---|
| JDK | 25 |
| Maven | 3.9+ |
| Docker (with Compose v2) | needed for PostgreSQL and for Testcontainers in the tests |
| Node.js | 20.19+ / 22.12+ (developed with 24) |

## Running locally

### 1. Start PostgreSQL

```bash
docker compose up -d
```

This starts PostgreSQL 18 on `localhost:5432` (database/user/password: `marketplace`).
Data is kept in the `marketplace-postgres-data` volume. `docker compose down -v` wipes it.

### 2. Run the backend

```bash
cd marketplace-service
mvn spring-boot:run
```

- API: http://localhost:8080/api/products
- Swagger UI: http://localhost:8080/swagger-ui.html (OpenAPI JSON: `/v3/api-docs`)

Flyway creates the schema and seeds six products on first start. The connection can be overridden
with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.

### 3. Run the frontend

```bash
cd marketplace-web
npm install
npm run dev
```

Open http://localhost:5173. The Vite dev server proxies `/api` to `localhost:8080`.

## Running tests

```bash
# Backend: unit tests + integration tests (Testcontainers starts its own PostgreSQL, Docker must be running)
cd marketplace-service
mvn test

# Frontend: unit/component tests and production build
cd marketplace-web
npm test
npm run build
```

## Trying the API by hand

Cart, checkout and order endpoints need an `X-Session-Id` header containing any UUID:

```bash
S=$(uuidgen)   # or any UUID
curl -s -H "X-Session-Id: $S" -H "Content-Type: application/json" \
     -d '{"productId":1,"quantity":2}' localhost:8080/api/cart/items
curl -s -X POST -H "X-Session-Id: $S" localhost:8080/api/checkout
curl -s -H "X-Session-Id: $S" localhost:8080/api/orders
```

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
│       ├── checkout/           cart → order, transactional
│       ├── order/              orders and immutable order lines
│       ├── session/            X-Session-Id → SessionId (the one place to swap for real auth later)
│       └── common/             error codes and the global API error format
└── marketplace-web/            React 19 + TypeScript + Vite frontend
    └── src/
        ├── api/                typed fetch client and DTO types
        ├── components/         ProductList, CartPanel, OrderConfirmation, ErrorMessage
        ├── session.ts          anonymous session UUID in localStorage
        └── App.tsx             top-level state (cart, current view)
```
