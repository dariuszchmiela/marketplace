# Marketplace Interview Lab — SPEC
## Projekt treningowy pod techniczną rozmowę Senior Fullstack Java/Kotlin + React

## 1. Cel projektu

To nie ma być duży projekt portfolio ani kopia Allegro.

To ma być **działająca aplikacja treningowa**, na której można praktycznie przećwiczyć tematy najbardziej prawdopodobne na rozmowie technicznej:

- Java / Stream API / Collections
- clean code / SOLID / code review
- Spring Boot
- REST / OpenAPI
- transakcje
- HTTP integrations
- timeout / retry / circuit breaker / fallback
- idempotency
- concurrency / race conditions
- optimistic locking
- threads / thread pools / virtual threads
- Java Memory Model
- JVM / heap / stack / GC
- PostgreSQL / connection pool
- Kafka / asynchronous events
- transactional outbox
- sessions / authentication / authorization
- React / TypeScript
- frontend state / hooks / API errors / duplicate submit
- monitoring / diagnostics
- system design
- opcjonalnie AI agent / tool calling

Projekt ma również generować naturalne przykłady do odpowiedzi na rozmowie.

---

# 2. Zasady pracy

## Role

### ChatGPT
- koordynator,
- architekt,
- przygotowuje spec i kolejne bloki pracy,
- przygotowuje duże prompty dla Claude Code,
- po każdym większym bloku robi review,
- nie poprawia kodu za Claude Code,
- przy błędach przygotowuje prompt naprawczy dla Claude Code,
- pilnuje scope i tematów rekrutacyjnych.

### Claude Code
- główny wykonawca implementacji,
- analizuje istniejący kod przed zmianami,
- implementuje cały uzgodniony blok,
- sam poprawia błędy wykryte podczas implementacji,
- uruchamia testy,
- uruchamia aplikację / smoke test, jeśli etap tego wymaga,
- na końcu podaje summary zmian, testów i ewentualnych problemów,
- NIE robi commit ani push bez wyraźnego polecenia.

### Użytkownik
- operator Claude Code / Git / IDE,
- przekazuje Claude Code prompty,
- po wykonaniu przekazuje ChatGPT wynik, diff albo dostęp do repo,
- zatwierdza przejście do kolejnego bloku.

## Cykl

```text
SPEC
  ↓
duży prompt do Claude Code
  ↓
implementacja + testy
  ↓
wynik / diff
  ↓
review ChatGPT
  ↓
ewentualny prompt poprawkowy
  ↓
testy + review
  ↓
commit
  ↓
learning / interview pass
  ↓
kolejny blok
```

## Reguły

- delivery-first,
- bez mikro-kroków,
- bez overengineeringu,
- jeden spójny blok implementacyjny naraz,
- każdy etap musi kończyć się działającym stanem repo,
- commit dopiero po review,
- commit message po angielsku,
- brak Lomboka,
- jawny i czytelny kod,
- testy tam, gdzie chronią realne zachowanie,
- nie dodajemy technologii tylko po to, żeby były w CV.

---

# 3. Główna domena

```text
User Session
     |
     v
Products
     |
     v
Cart
     |
     v
Checkout
   /      \
Stock    Pricing
     \    /
      Order
        |
        v
     Payment
        |
        v
 Order Status
        |
        v
      Events
```

Przykładowy flow:

1. użytkownik otwiera listę produktów,
2. dodaje produkty do koszyka,
3. zmienia ilość,
4. przechodzi do checkout,
5. backend ponownie sprawdza ceny i dostępność,
6. system rezerwuje stock,
7. tworzy zamówienie,
8. wykonuje płatność,
9. ustawia finalny status zamówienia,
10. publikuje event,
11. frontend prezentuje wynik.

---

# 4. Repozytorium

Docelowo:

```text
marketplace-interview-lab/
├── README.md
├── docker-compose.yml
├── docs/
│   ├── architecture.md
│   ├── interview-map.md
│   └── decisions/
├── marketplace-service/
├── payment-service/
└── marketplace-web/
```

## marketplace-service

Główna aplikacja.

Technologie:
- Java,
- Spring Boot,
- Spring Web,
- Spring Data JPA,
- PostgreSQL,
- Flyway,
- Bean Validation,
- OpenAPI,
- JUnit 5,
- Mockito,
- Testcontainers.

Bez Lomboka.

## payment-service

Dodawany później.

Technologie:
- Kotlin,
- Spring Boot,
- REST,
- testy.

Ma symulować prawdziwą zależność:
- sukces,
- timeout,
- wolną odpowiedź,
- 5xx,
- duplikat payment request.

## marketplace-web

- React,
- TypeScript,
- prosty toolchain,
- bez rozbudowanego design systemu,
- UI funkcjonalne, nie projekt graficzny.

---

# 5. Model domenowy — MVP

## Product

Pola minimalne:
- `id`
- `name`
- `description`
- `price`
- `availableQuantity`
- `version`

`price` nie może używać `double`.

## Cart

- identyfikowany przez użytkownika / sesję,
- zawiera `CartItem`,
- `CartItem`:
  - productId
  - quantity

Koszyk nie jest źródłem prawdy dla:
- aktualnej ceny,
- aktualnego stocku.

Backend sprawdza je ponownie przy checkout.

## Order

Przykładowe statusy:

```text
NEW
PAYMENT_PENDING
PAID
PAYMENT_FAILED
CANCELLED
```

OrderLine zapisuje cenę używaną w momencie zakupu.

## Payment

Na początku brak osobnej encji w marketplace-service, dopóki integracja nie zostanie dodana.

---

# 6. API — kierunek

MVP:

```text
GET    /api/products
GET    /api/products/{id}

GET    /api/cart
POST   /api/cart/items
PUT    /api/cart/items/{productId}
DELETE /api/cart/items/{productId}

POST   /api/checkout

GET    /api/orders
GET    /api/orders/{id}
```

Dokładny contract może zostać dopracowany podczas implementacji, ale zmiany muszą być uzasadnione.

---

# 7. Fazy projektu

# PHASE 1 — Core marketplace vertical slice

## Cel

Działający flow:

```text
Product list
→ Add to cart
→ View/update cart
→ Checkout without external payment
→ Order saved in PostgreSQL
→ Order confirmation in React
```

## Backend

- Spring Boot Java.
- Product.
- Cart.
- Order.
- PostgreSQL.
- Flyway.
- REST/OpenAPI.
- validation.
- sensowny error model.
- tests.
- Testcontainers dla ważnych integration tests.

## Frontend

- lista produktów,
- add to cart,
- cart,
- zmiana quantity,
- checkout,
- ekran potwierdzenia zamówienia,
- loading/error states.

## Tematy rozmowy

- Stream API,
- collections,
- DTO/domain separation,
- REST,
- validation,
- money,
- transactions,
- React state,
- useEffect,
- error handling,
- clean code.

---

# PHASE 2 — Payment integration + resilience

Dodajemy osobny `payment-service` w Kotlinie.

## Scenariusze payment-service

Endpoint może symulować:

- success,
- slow response,
- timeout,
- HTTP 500,
- declined payment.

## marketplace-service

Integracja HTTP z payment-service.

Ćwiczymy:

- connect/read/response timeout,
- retry,
- backoff,
- circuit breaker,
- fallback — tylko tam, gdzie ma sens,
- state orderu,
- idempotency key,
- duplicate checkout,
- failure recovery.

## Ważny przypadek

```text
Payment succeeded,
but marketplace-service did not receive the response.
```

Aplikacja musi mieć sposób odzyskania poprawnego stanu bez podwójnego charge.

---

# PHASE 3 — Concurrency lab

## Scenariusz 1

Dwóch użytkowników kupuje ostatnią sztukę produktu.

Ćwiczymy:

- race condition,
- DB transaction,
- optimistic locking,
- conflict handling.

## Scenariusz 2

Równoległe pobranie:

- price,
- stock,
- opcjonalnie delivery info.

W kolejnych wariantach:

1. synchronicznie,
2. `CompletableFuture`,
3. virtual threads.

Mierzymy i porównujemy zachowanie.

## Tematy rozmowy

- threads,
- pools,
- virtual threads,
- blocking I/O,
- atomicity,
- visibility,
- JMM,
- connection pools,
- downstream limits.

---

# PHASE 4 — Kafka + outbox

Po poprawnym utworzeniu zamówienia:

```text
OrderCreated
OrderPaid
OrderCancelled
```

Dodajemy:

- Kafka,
- producer,
- prosty consumer,
- transactional outbox.

Cel:
- DB commit i event nie mogą zostać przypadkowo rozjechane.

Ćwiczymy:

- eventual consistency,
- duplicates,
- consumer idempotency,
- ordering,
- retry,
- DLQ jako temat do rozważenia.

Nie tworzymy wielu mikroserwisów tylko dla pokazania Kafki.

---

# PHASE 5 — Security / sessions

Dopiero gdy core działa.

Dodajemy prosty model użytkownika i authentication.

Zakres:

- session lub token — decyzja poprzedzona analizą,
- authorization do własnego cart/order,
- CORS,
- CSRF zależnie od modelu auth,
- secure cookies, jeśli używana sesja,
- PII/logging,
- security headers, jeśli potrzebne.

Tematy:
- authentication vs authorization,
- session vs JWT,
- CORS vs CSRF,
- ownership of resources,
- duplicate/replay protection.

---

# PHASE 6 — JVM / production lab

Nie dokładamy sztucznych feature'ów. Używamy istniejącej aplikacji.

Scenariusze:

## Load

- większy ruch na products,
- większy ruch na checkout,
- wolny payment-service.

Obserwujemy:

- latency p50/p95/p99,
- CPU,
- heap,
- GC,
- threads,
- connection pool,
- HTTP client,
- database pool.

## Controlled problems

Tworzymy osobne treningowe scenariusze / branch lub test harness:

- unbounded cache,
- blocked threads,
- connection pool exhaustion,
- too-small / too-large executor,
- slow downstream.

Cel:
- nauczyć się diagnozować,
- nie zostawiać celowych bugów w głównej wersji aplikacji.

---

# PHASE 7 — opcjonalny AI Shopping Assistant

AI dopiero po przećwiczeniu core technicznego.

## Cel

Agent rozumie request użytkownika, np.:

> "Find me a laptop under 5000 PLN and add the best option to my cart."

Agent nie ma bezpośredniego dostępu do bazy.

Dostaje tools:

- `searchProducts`
- `getProductDetails`
- `getCart`
- `addToCart`
- opcjonalnie `removeFromCart`

Możliwe rozwinięcia:

- LangChain4j,
- local model albo provider przez abstraction,
- tool calling,
- session/context,
- guardrails,
- observability.

Nie jest to priorytet przed drugim etapem Allegro.

---

# 8. Interview map

`docs/interview-map.md` ma mapować kod do tematów rozmowy.

Przykład:

| Interview topic | Project example |
|---|---|
| Stream API | cart → order lines |
| HashMap | product lookup by ID |
| equals/hashCode | domain/value types |
| transactions | checkout/order |
| optimistic locking | last product |
| idempotency | duplicate checkout/payment |
| HTTP timeout | payment-service |
| retry | temporary payment error |
| circuit breaker | repeated payment failures |
| thread pools | async checkout experiments |
| virtual threads | blocking integrations |
| JMM | concurrency lab |
| GC | load/memory lab |
| sessions | authenticated cart |
| Kafka | OrderCreated |
| outbox | reliable event publication |
| React state | cart/checkout |
| useEffect | API synchronization |
| security | cart/order ownership |

---

# 9. Code review lab

Po każdym większym etapie robimy osobny interview pass.

ChatGPT może:
- wskazać fragment kodu,
- poprosić użytkownika o review po angielsku,
- dopiero później pokazać brakujące problemy.

Możemy również mieć katalog:

```text
docs/code-review-exercises/
```

z celowo złymi snippetami, ale nie mieszamy złego kodu z produkcyjną wersją aplikacji.

Kategorie problemów:

- correctness,
- SOLID,
- naming,
- transaction boundaries,
- concurrency,
- HTTP,
- resilience,
- security,
- performance,
- memory,
- logging,
- testing.

---

# 10. Definition of Done dla każdego bloku

Claude Code przed zakończeniem bloku ma:

1. przeanalizować istniejący kod,
2. zaimplementować cały uzgodniony scope,
3. nie rozszerzać scope bez potrzeby,
4. uruchomić backend tests,
5. uruchomić frontend tests/build, jeśli dotyczy,
6. uruchomić integration tests, jeśli dotyczy,
7. wykonać sensowny smoke test działającego flow,
8. poprawić znalezione przez siebie błędy,
9. sprawdzić `git diff`,
10. nie commitować,
11. podać:
   - summary,
   - changed files,
   - tests,
   - manual verification,
   - decisions,
   - known limitations / remaining issues.

Dopiero potem ChatGPT robi review.

## Stały workflow handoff po każdym bloku Claude Code

Obowiązuje po każdym bloku implementacyjnym Claude Code (wszystkie fazy, także prompty poprawkowe):

1. `claude-result.md` (w root repo) jest aktualizowany finalnym raportem z bloku.
2. Wszystkie zmiany projektu — nowe, zmodyfikowane i usunięte pliki, w tym dokumentacja i konfiguracja — są dodane do stage (`git add`).
3. `claude-result.md` pozostaje niezastage'owany / untracked (nie trafia do commita).
4. Claude Code NIE robi commita.
5. Claude Code NIE robi pusha.
6. Finalna weryfikacja obejmuje:
   - `git status --short`,
   - odpowiednie testy / buildy (backend, frontend),
   - podsumowanie w `claude-result.md` (zmiany, testy, wyniki, `git status --short`, `git diff --cached --stat`).

Commit wykonuje użytkownik dopiero po review.

---

# 11. Czego NIE robimy na początku

- Kubernetes.
- Terraform.
- pełny cloud deployment.
- service mesh.
- kilkanaście mikroserwisów.
- event sourcing.
- CQRS bez realnej potrzeby.
- Redis tylko dlatego, że istnieje.
- Elasticsearch tylko dlatego, że to marketplace.
- rozbudowane UI.
- AI przed zbudowaniem core.
- frameworków i abstrakcji „na przyszłość”.

---

# 12. Pierwszy milestone

Po pierwszym dużym bloku aplikacja ma pozwalać:

1. uruchomić PostgreSQL,
2. uruchomić backend,
3. uruchomić React,
4. zobaczyć produkty,
5. dodać produkt do koszyka,
6. zmienić quantity,
7. zrobić checkout,
8. zobaczyć utworzone zamówienie,
9. uruchomić testy i dostać zielony wynik.

To ma być pierwsza działająca pionowa ścieżka.

---

# 13. Nauka po milestone

Po review pierwszego milestone NIE dokładamy od razu kolejnego feature'u.

Najpierw robimy krótki technical pass:

- Stream API na danych cart/product.
- code review wybranego service.
- `@Transactional`.
- React state / `useEffect`.
- potencjalne race conditions.
- co zmieni się po dodaniu payment-service.

Dopiero później Phase 2.
