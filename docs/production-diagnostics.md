# Production diagnostics (Phase 6)

How to see what the marketplace is doing in production and how to find a bottleneck from its symptoms.
There are two separate parts:

- **Production observability.** Part of the application: Actuator, Micrometer metrics in Prometheus format,
  distributed tracing (W3C trace context), log correlation, and health groups.
- **The production/JVM lab.** Training only (`marketplace-service/src/test/.../productionlab`, `tools/production-lab`).
  It reproduces the classic failures (pool exhaustion, a slow downstream, blocked threads, executor saturation,
  retained memory, GC pressure, CPU-bound work) in a controlled way. No intentional bug exists in the application code.

All numbers below were measured once on a development laptop (16 cores, Windows, JDK 25.0.4, services started with
`java -jar`). They show the *shape* of each effect and are **not benchmarks**.

---

## 1. Quick start

```bash
docker compose up -d                              # PostgreSQL + Kafka
docker compose --profile observability up -d      # + Prometheus :9090, Grafana :3000, Jaeger :16686

# services with span export to Jaeger (OTLP http://localhost:4318)
cd payment-service        && TRACING_EXPORT_ENABLED=true mvn spring-boot:run
cd marketplace-service    && TRACING_EXPORT_ENABLED=true SESSION_COOKIE_SECURE=false PAYMENT_FORWARD_SCENARIO_HEADER=true mvn spring-boot:run
cd order-activity-service && TRACING_EXPORT_ENABLED=true ORDER_ACTIVITY_SIMULATION_ENABLED=true mvn spring-boot:run

# traffic
java tools/production-lab/LoadGenerator.java --scenario browse --concurrency 20 --requests 5000
```

| What | Where |
|---|---|
| Grafana dashboard "Marketplace - production diagnostics" | http://localhost:3000 (anonymous viewer; admin/admin) |
| Prometheus (targets, ad-hoc PromQL) | http://localhost:9090/targets |
| Jaeger (traces) | http://localhost:16686 |
| Metrics of one service | `curl -H "Authorization: Bearer dev-only-management-token" localhost:8080/actuator/prometheus` |
| Dependency health (operators) | `curl -H "Authorization: Bearer dev-only-management-token" localhost:8080/actuator/health/dependencies` |

`MANAGEMENT_TOKEN` defaults to `dev-only-management-token` for local development. The services log a warning when
this default is in use, and `observability/prometheus/prometheus.yml` sends it. Anywhere else, set a real token and
give Prometheus a `credentials_file`.

Lab setup: the seeded products run out of stock after a few load runs. To top up stock in the **local** database:
`docker exec marketplace-postgres psql -U marketplace -d marketplace -c "update product set available_quantity = 1000000"`.

---

## 2. Management endpoints and their security boundary

| Endpoint | Access | Content |
|---|---|---|
| `/actuator/health`, `/health/liveness`, `/health/readiness` | public | status only (`UP`/`DOWN`), no components or details |
| `/actuator/health/dependencies` | management token | every dependency with details (passive checks) |
| `/actuator/prometheus`, `/actuator/metrics`, `/actuator/info`, `/actuator` | management token | metrics, build/JVM info |
| `env`, `configprops`, `beans`, `heapdump`, `threaddump`, `loggers`, `shutdown`, ... | **not exposed** (404) | dumps are taken locally with `jcmd` |

- The token is `Authorization: Bearer <MANAGEMENT_TOKEN>` (constant-time comparison, never logged). It is a separate
  secret from the payment service token and the order-activity API token: being able to scrape metrics must not let
  anyone create payments.
- In marketplace-service, a dedicated Spring Security chain handles `/actuator/**`
  (`observability/ManagementSecurityConfiguration`, `@Order(1)`). The chain is stateless, so the browser session cookie
  is **not** a management credential. A logged-in shopper gets 401 there (tested). No CSRF is needed because a bearer
  token is never sent automatically by a browser.
- payment-service and order-activity-service use a servlet filter on `/actuator/*` for the same purpose
  (`observability/ManagementTokenFilter.kt`, `observability/ManagementSecurityConfiguration.java`).
- Alternative design, not chosen: a separate management port (`management.server.port`) reachable only from an internal
  network. It is fine in Kubernetes with NetworkPolicies, but here all ports sit on one laptop, and the token makes the
  boundary explicit and testable.

---

## 3. Metric inventory

Every tag has a small, fixed set of values. Metrics never carry these as labels: email, user id, session id, order id,
payment id, event id, idempotency key, trace id, or a raw URL. HTTP metrics use URI **templates** (`/api/orders/{id}`).
Tests enforce this (`BusinessMetricsIntegrationTest.noIdentifierOrSecretEverBecomesAMetricLabel`, and the consumer and
payment tests), and so did the live smoke check: 285 distinct label pairs across the three services, 0 UUIDs, 0 emails,
0 tokens, and 0 numeric URI segments.

### Framework metrics (Spring Boot / Micrometer)

| Area | Prometheus names | Notes |
|---|---|---|
| HTTP server | `http_server_requests_seconds_{bucket,count,sum,max}{method,uri,status,outcome,exception}` | histogram buckets → p50/p95/p99 in PromQL |
| HTTP client (payment calls) | `http_client_requests_seconds_*{uri,status,outcome,client_name}` | one per **attempt** (retries visible) |
| JVM | `jvm_memory_used_bytes{area,id}`, `jvm_memory_usage_after_gc`, `jvm_gc_pause_seconds_*{action,cause,gc}`, `jvm_gc_memory_allocated_bytes_total`, `jvm_threads_{live,daemon,peak}_threads`, `jvm_threads_states_threads{state}`, `jvm_classes_loaded_classes` | only platform threads are counted |
| Process / system | `process_cpu_usage`, `system_cpu_usage`, `process_cpu_time_ns_total` | |
| HikariCP | `hikaricp_connections_{active,idle,pending,max,min}`, `hikaricp_connections_acquire_seconds_*`, `hikaricp_connections_timeout_total` | pending > 0 means threads are waiting for a connection |
| Tomcat | `tomcat_threads_{busy,current,config_max}_threads` | `server.tomcat.mbeanregistry.enabled=true` (marketplace) |
| Kafka producer/consumer | `kafka_producer_*`, `kafka_consumer_fetch_manager_records_lag{topic,partition}`, `..._records_lag_max`, `spring_kafka_template_seconds_*`, `spring_kafka_listener_seconds_*` | the client exports each topic twice (`marketplace.order-events` and `marketplace_order-events`), so filter on the real name |
| Resilience4j | `resilience4j_circuitbreaker_state{state}`, `..._calls_seconds_*{kind}`, `..._not_permitted_calls_total`, `resilience4j_retry_calls_total{kind}` | `resilience4j-micrometer`, bound in `PaymentClientConfiguration` |

### Application metrics

| Metric | Type, tags | Code | Question it answers |
|---|---|---|---|
| `marketplace.checkout.total` | counter `result` = PAID, PAYMENT_FAILED, PAYMENT_UNKNOWN, STOCK_CONFLICT, REJECTED, REPLAYED, ERROR | `checkout/CheckoutMetrics`, `CheckoutService` | what happens to checkouts? |
| `marketplace.checkout.duration` | timer + histogram, `result` | same | how long does the whole orchestration take (p99)? |
| `marketplace.checkout.step.duration` | timer `step` = place_order, payment_call, apply_outcome | same | where did the time go? The DB transactions vs the remote call |
| `marketplace.stock.conflict` | counter | same | optimistic-lock races on product stock |
| `payment.client.calls` / `payment.client.duration` | counter / timer + histogram, `operation` = pay, lookup; `result` = success, declined, not_found, timeout, server_error, connection_error, circuit_open, other | `payment/PaymentClientMetrics` | how does the payment downstream behave, per **logical** call (retries included)? |
| `outbox.pending.count`, `outbox.pending.retrying`, `outbox.oldest.pending.age` (seconds) | gauges | `outbox/OutboxMetrics` | is event publication keeping up? (outbox lag) |
| `outbox.publish.success` / `outbox.publish.failure` | counters `event_type` | same | Kafka acknowledgements vs failed sends |
| `outbox.publish` | observation timer `event_type`, `error` | `outbox/OutboxTracing` | time from send to broker ack, per row |
| `outbox.batch.duration` / `outbox.batch.size` | timer `outcome` = published, failed, empty / summary | `OutboxMetrics` | how long claimed rows stay locked |
| `sessions.active` | gauge | `auth/SessionMetrics` | non-expired server-side sessions |
| `auth.login.success`, `auth.login.failure`, `security.csrf.rejected`, `security.authentication.required`, `security.management.rejected` | counters, no tags | `observability/SecurityMetrics` | attacks, broken clients |
| `order.events.processed`, `.duplicate`, `.stale`, `.retry` | counters `eventType` | order-activity `observability/OrderEventMetrics` | what did the consumer do? |
| `order.events.dead_lettered` | counter `eventType`, `reason` = permanent, retries_exhausted | same | events that need a human |
| `order.events.processing` | timer `eventType`, `result` | same | processing time per delivery attempt |
| `security.internal_api.rejected{reason}`, `security.management.rejected` | counters | same | callers without a token |
| `payment.service.payments` | counter `status`, `result` = created, replayed | payment-service `observability/PaymentMetrics` | payments processed, idempotent replays |
| `payment.simulation.scenario`, `security.service_auth.rejected{reason}` | counters | same | requested failure scenarios, callers without a service token |

Design rules used everywhere:

- **Bounded tags only.** `eventType` comes from a Kafka header, i.e. from outside the service, so unknown values become
  `other` and a producer cannot create unbounded label values (tested with a random "Evil-…" type).
- **Counters are registered up front with value 0.** A series that first appears with a non-zero value is invisible
  to `rate()`/`increase()`. The smoke test hit exactly this: before the fix,
  `increase(payment_client_calls_total{result="circuit_open"}[3m])` reported 0 while the circuit had rejected 13 calls.
  Timers stay lazy, because every histogram timer carries its buckets.
- **Gauges that query the database** (outbox backlog, active sessions) use a cached snapshot (5 s / 15 s). The queries
  are plain MVCC reads that are index-backed and capped with `LIMIT`. They never lock, and they never wait for the
  publisher's `FOR UPDATE SKIP LOCKED` claim (tested: the scrape returns in < 1 s while a claim is held). Migration
  `V7__outbox_observability.sql` adds the partial index `idx_outbox_pending_by_id`.

---

## 4. Percentiles: why the average lies

Percentile histograms are enabled only where latency matters: `http.server.requests`, `http.client.requests`,
`marketplace.checkout.duration`, and `payment.client.duration`. Bucket boundaries are limited with
`minimum/maximum-expected-value`. Prometheus computes the quantiles:

```promql
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application="marketplace-service",uri!~"/actuator.*"}[1m])))
```

Why buckets and not client-side `percentiles: 0.99`?
- **Client-side percentiles can't be combined.** Computed inside one JVM, they cannot be aggregated across instances or
  time windows (the average of two p99s is meaningless).
- **Buckets can.** They sum across instances and let you choose the window afterwards.
- **The cost is precision.** A quantile from buckets is an estimate. The lab shows this: exact p50 1091 ms vs
  Micrometer's 1137 ms in the HTTP downstream experiment.

The measured case for "tail first" (live stack, 5 concurrent shoppers, checkout, the SLOW scenario on every 20th payment
= 5%):

| | mean | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| normal payment | 48.9 ms | 48.0 ms | 67.1 ms | 68.9 ms | 74.5 ms |
| 5% slow payment | 119.0 ms (×2.4) | 40.0 ms | 50.4 ms | **2049.1 ms (×30)** | 2069.2 ms |

The median did not move. The mean doubled. p99 grew thirty-fold, from 69 ms to the 2 s read timeout. Five percent of
users waited 2 s and got "payment being verified" (PAYMENT_UNKNOWN). An alert on the average would probably have stayed
silent.

---

## 5. Health: liveness, readiness, dependencies

| Group | Contents | Used by | Principle |
|---|---|---|---|
| liveness | `livenessState` | orchestrator restart decision | never depends on other systems: a DB outage must not restart every instance |
| readiness | `readinessState`, `db` | load balancer | only what every request needs; marketplace + order-activity need PostgreSQL |
| dependencies (token) | marketplace: `db`, `outbox`, `paymentService`; order-activity: `db`, `kafkaConsumer`; payment: `diskSpace`, `ping` | operators, dashboards | full picture, with details |

- **Passive checks, no probes.** `paymentService` reads the circuit breaker, which already records every real call.
  `outbox` reads the cached backlog. `kafkaConsumer` reads the listener containers' state. The health endpoint never
  calls payment-service or Kafka itself. Health is polled constantly, by every instance and every monitor. An active
  probe would add load exactly when the dependency struggles, and a slow probe would make our own health slow, so the
  failure spreads upstream.
- **DEGRADED instead of DOWN for dependencies.** A custom status that ranks *below* UP for the public endpoint
  (`management.endpoint.health.status.order: down, out-of-service, up, degraded, unknown`) and *above* UP in the
  `dependencies` group. Verified live during a Kafka outage: after 62 s the dependencies view showed
  `outbox: DEGRADED (pending 6, retrying 3, oldest 62 s)` while `/actuator/health` and readiness stayed `UP` (HTTP 200).
  Without payment-service the marketplace still serves the catalog, carts and orders, and checkout degrades safely.
  Without Kafka, checkout works and events wait in the outbox. Taking every instance out of the load balancer would
  turn a partial outage into a total one.

---

## 6. Tracing and log correlation

Stack: Micrometer Tracing with the OpenTelemetry bridge (`spring-boot-starter-opentelemetry`), W3C `traceparent`
propagation, and OTLP/HTTP export to Jaeger. Sampling is 100% in this lab (`TRACING_SAMPLING_PROBABILITY`). Spans are
always created and propagated. They are exported only when `TRACING_EXPORT_ENABLED=true`
(`management.tracing.export.otlp.enabled`).

> Boot 4 pitfall found in this phase: `management.tracing.export.enabled=false` also disables the propagators. The
> ContextPropagators became a no-op, so the incoming `traceparent` was ignored. Only the OTLP-specific switch is used.

A real checkout trace from the smoke test (Jaeger, 13 spans, 3 services):

```
     +0.0ms  44.7ms marketplace-service     http post /api/checkout [server]
     +2.8ms  39.9ms marketplace-service       secured request
    +22.4ms   4.2ms marketplace-service         http post [client]                      ← RestClient observation
    +23.7ms   2.0ms payment-service               http post /api/payments [server]      ← continued via traceparent
   +319.6ms   8.3ms marketplace-service         outbox publish OrderCreated [consumer]  ← restored from outbox_event.trace_parent
   +319.7ms   8.1ms marketplace-service           marketplace.order-events send [producer]
   +350.6ms   7.3ms order-activity-service          marketplace.order-events process [consumer]
   +352.2ms   8.2ms marketplace-service         outbox publish OrderPaid [consumer]
   +352.3ms   8.0ms marketplace-service           marketplace.order-events send [producer]
   +406.0ms   8.8ms order-activity-service          marketplace.order-events process [consumer]
```

### The outbox decision

The outbox puts a time gap between the HTTP request and the Kafka send. The publisher runs later on a scheduler thread,
where the request's context no longer exists. The chosen approach:

1. **The request trace.** `OutboxWriter` stores the current W3C traceparent in `outbox_event.trace_parent`
   (`V7__outbox_observability.sql`). The value is 55 characters, with no tracestate, baggage or business data, and it
   is not part of the event JSON.
2. **The asynchronous boundary.** `OutboxTracing.observePublish` treats the row like a message on a queue. The row is
   the carrier of a Micrometer `ReceiverContext`, so the tracing handler starts the `outbox publish <type>` span with
   the stored span as its parent. This is real continuity, not an imitation: the parent span id is exactly the span
   that wrote the row. The trace honestly shows the gap (+300 ms here, minutes during a Kafka outage), which *is* the
   outbox lag.
3. **The producer span.** Spring Kafka's observation (`spring.kafka.template.observation-enabled`) creates the send span
   and writes `traceparent` into the Kafka record headers.
4. **The consumer span.** `spring.kafka.listener.observation-enabled` in order-activity-service continues it. The
   listener's log lines carry the checkout's trace id, and a dead-lettered record carries the trace of the failed
   processing.

Rows written before Phase 6, or without tracing, have `trace_parent = NULL`. The publish span then starts a new trace.
The alternative, a new trace per publication *linked* to the original, would be the choice for batch publishers that
merge many requests into one message. Tests: `TracePropagationIntegrationTest` (client traceparent → payment call
header → outbox rows → Kafka headers, same trace id) and `ConsumerObservabilityIntegrationTest` (listener continues the
producer's trace).

### Logs

`logging.pattern.correlation: "[traceId=%X{traceId:-} spanId=%X{spanId:-}] "` in all three services. The MDC is filled
by the tracing bridge, so every existing `key=value` log line gets the ids automatically. Searching for one trace id in
the smoke logs found 8 marketplace lines, 1 payment-service line and 4 order-activity lines for one checkout. The Phase 5
rules still apply: no passwords, cookies, Authorization headers, CSRF tokens, service tokens or session ids in logs.
The smoke check found 0 occurrences of any secret in all three logs.

---

## 7. Asynchronous flow: outbox lag, Kafka consumer lag, projection correctness

These are three different questions:

| Signal | Metric | What it tells | What it does not tell |
|---|---|---|---|
| Outbox lag | `outbox_oldest_pending_age_seconds`, `outbox_pending_count` | events are written but not yet on Kafka (broker down, publisher stuck, poison row) | anything about consumers |
| Kafka consumer lag | `kafka_consumer_fetch_manager_records_lag{topic,partition}` | records on the topic that the consumer has not reached yet | whether what it consumed was applied correctly |
| Projection correctness | `order_events_processed/duplicate/stale/retry/dead_lettered_total` | what happened to each consumed event | how far behind the consumer is |

Consumer lag 0 with a growing DLT still means a projection that is missing orders. Lag stays low while events are being
dead-lettered, because the DLT path commits offsets.

Kafka outage, measured live:
- Kafka stopped, 10 checkouts: all `PAID` (the outbox decouples the checkout from Kafka).
- `outbox_pending_count` 20, then `outbox_oldest_pending_age_seconds` 4 s → 30 s → 64 s, and publish failures 1 → 5 → 12.
- Readiness stayed UP throughout.
- Kafka started: the backlog drained to 0 within ~15 s. The consumer processed everything and its lag returned to 0.

### Outbox performance trade-off

The publisher holds one transaction (and its `FOR UPDATE SKIP LOCKED` row locks and a DB connection) while it waits for
the Kafka acks of the batch. `outbox_batch_duration_seconds` measures exactly this lock time, and the `outbox.publish`
timer measures it per row.

- Normally: ~8 ms per record (trace above).
- During the outage: up to `max.block.ms` = 5 s for a failing batch, after which the batch stops.
- It is acceptable here: a background job, a bounded batch, and a failing batch that stops at the first error.

A **lease/claim design** becomes preferable when:
- batch durations approach the connection pool's patience;
- several publisher instances are needed;
- sends must be pipelined (async sends with one flush);
- or the lock time shows up in `hikaricp_connections_pending`.

In that design you mark rows `claimed_by/claimed_until` in a short transaction, send without a transaction, then mark
them published. The measurements did not show a correctness problem, so the design was not changed in this phase.

---

## 8. JVM diagnostic runbook (JDK 25)

All commands were run against the live marketplace JVM during the smoke test.

```bash
jcmd -l                                                  # PIDs of running JVMs (also: jps -l)
jcmd <pid> VM.version                                    # OpenJDK 64-Bit Server VM version 25.0.4.1+1-LTS
jcmd <pid> VM.flags                                      # -XX:+UseG1GC -XX:MaxHeapSize=17112760320 (1/4 of RAM: no -Xmx set!)
jcmd <pid> VM.command_line                               # how the JVM was started
jcmd <pid> GC.heap_info                                  # garbage-first heap total reserved 16711680K, committed 196608K, used 73764K
jcmd <pid> GC.class_histogram | head -20                 # instances/bytes per class (forces a full GC: "live" objects)
jcmd <pid> Thread.print > threads.txt                    # classic thread dump (platform threads + carriers), same as jstack
jcmd <pid> Thread.dump_to_file -format=json threads.json # Java 21+: includes virtual threads
jcmd <pid> JFR.start name=diag settings=profile filename=diag.jfr   # optional: duration=60s maxsize=100M
jcmd <pid> JFR.check
jcmd <pid> JFR.dump name=diag                            # snapshot while recording continues
jcmd <pid> JFR.stop name=diag
jfr summary diag.jfr                                     # event types and counts
jfr print --events jdk.ExecutionSample diag.jfr          # CPU samples → hot methods
jfr print --events jdk.JavaExceptionThrow diag.jfr       # exception hot spots
jcmd <pid> GC.heap_dump -gz=1 heap.hprof.gz              # heap dump (see the caution below)
```

JFR can also be started with the JVM: `java -XX:StartFlightRecording=name=app,settings=profile,maxsize=200M,dumponexit=true,filename=app.jfr -jar ...`.

**Operational caution for heap dumps.**
- **Large:** the whole live heap. 34 MB gzipped here; gigabytes in production.
- **Stop-the-world:** the process is paused while it is written (1.2 s here); on a large heap this can fail health checks.
- **Contain everything in memory:** tokens, session data, personal data. Treat a dump like a database backup: protected
  storage, access control, deleted after analysis, never committed (`*.hprof`, `*.jfr` are in `.gitignore`).
- Prefer `GC.class_histogram` first: it is cheap, says "what" but not "who holds it", and needs no file.

**What the live recording showed.** 11 s, profile settings, load running, 2.8 MB. It contained ExecutionSample 552,
ObjectAllocationSample 2094, SocketRead 1705, JavaExceptionThrow 1682, ThreadPark 1001, GC 7, GCHeapSummary 14 and
CPULoad 9 events. Two findings came straight out of it:

1. **Hot method: `BCrypt.key`** (87 of 552 CPU samples). The load generator registers a user per worker, and BCrypt is
   CPU-heavy by design. Registration and login are the most expensive endpoints per request, which is one reason why
   they need rate limiting (deferred, see Phase 5).
2. **~1,300 exceptions in 11 s from Spring Data.**
   - The source: `@EntityGraph(attributePaths = "items")` on `CartRepository.findBySessionId` (and on
     `OrderRepository.findBySessionIdAndCheckoutIdempotencyKey`). Spring Data first asks JPA for a *named* graph
     `Cart.findBySessionId`, Hibernate throws `IllegalArgumentException("No EntityGraph with given name …")`, and the
     reflective proxy wraps it (`InvocationTargetException`). Spring catches it and falls back to the ad-hoc graph.
   - The cost: about two stack traces per cart/order lookup, invisible in the logs and visible only in JFR. It is
     functionally harmless.
   - **Fixed after Phase 6.** The four hot reads now use explicit JPQL fetch joins
     (`select distinct … left join fetch …`). Verified with the same kind of JFR run: "No EntityGraph with given
     name" went from 1,205 to **0** (`IllegalArgumentException` 1,209 → 4) for the same workload. See
     `FetchJoinReadsIntegrationTest` for the one-query and ownership checks.

Thread dump of the live marketplace: 66 threads (RUNNABLE 13, TIMED_WAITING 19, WAITING 13). They included
http-nio 12, HikariPool housekeeper 1, kafka-producer 1, scheduling 1, and OTel exporter 3.

### Reading a thread dump

| Pattern | Meaning |
|---|---|
| `java.lang.Thread.State: BLOCKED (on object monitor)` + `- waiting to lock <0x…>` | waiting for a `synchronized` monitor; find the thread with `- locked <0x…>` (same address) and read *its* stack |
| `WAITING (parking)` + `- parking to wait for <0x…> (a …ReentrantLock$NonfairSync)` | waiting for a `java.util.concurrent` lock; `jdk.JavaMonitorEnter`/`jdk.ThreadPark` in JFR show who waited how long |
| `WAITING (parking)` in `HikariPool.getConnection` / `ConcurrentBag.borrow` | waiting for a DB connection: pool exhausted |
| `WAITING (parking)` in `JdkClientHttpRequest.executeInternal` ← `PaymentClient.post` | waiting for payment-service (seen live, 5 request threads) |
| `TIMED_WAITING (sleeping)` | `Thread.sleep` |
| many `http-nio-*-exec` threads in `TaskQueue.poll` | idle request threads: the server is *not* busy |

Virtual threads: a classic `Thread.print` lists platform threads and the carrier `ForkJoinPool-1-worker-*` threads, not
parked virtual threads. The lab parked 10,000 virtual threads: `Thread.print` showed 16 carriers and none of the 10,000,
while `Thread.dump_to_file -format=json` (14.5 MB) listed all 10,000. Do not expect a Java 8-style dump to show "all
the work". JFR has `jdk.VirtualThreadPinned` / `jdk.VirtualThreadSubmitFailed` for virtual-thread problems.

### GC logging (unified logging, `-Xlog`)

```bash
java -Xmx256m -Xlog:gc:stdout tools/production-lab/MemoryDemo.java churn 5
java -Xmx256m "-Xlog:gc*:file=target/gc.log:time,uptime,level,tags" tools/production-lab/MemoryDemo.java churn 3
java -Xlog:help                                         # all tags and options
```

Observed with G1, 256 MB heap, ~10 GB/s of short-lived allocations:
- 347 `Pause Young (Normal) (G1 Evacuation Pause)` in 5 s and no full GC.
- Each young GC took the heap from ~156M back to ~7M.
- Pauses: min 0.57 ms, median 0.91 ms, max 3.9 ms; 337 ms of GC time in total.

The `gc*` log adds phases (Evacuate Collection Set, Merge Heap Roots, …), heap region counts and metaspace. This is
**GC pressure without a leak**: a high allocation rate, many cheap young collections, and a flat heap after GC.

---

## 9. Troubleshooting playbooks

Each playbook starts from a symptom, not a guess. Dashboard: "Marketplace - production diagnostics".

### High latency, CPU low → something is *waiting*

1. **Where is the tail?** `histogram_quantile(0.99, …http_server_requests_seconds_bucket…)` by `uri`. Is it all
   endpoints (a shared resource) or one (its dependency)?
2. **Are request threads busy?** `tomcat_threads_busy_threads` vs `_config_max_threads`. Busy threads with low CPU means
   the threads are waiting on something.
3. **Is the DB pool the queue?** `hikaricp_connections_active == max` and `pending > 0`,
   `hikaricp_connections_acquire_seconds_max` → go to the "DB pool exhausted" playbook.
4. **Is a downstream slow?** `payment_client_duration_seconds` p99 by `result`, and `result="timeout"` counts →
   go to the "Payment downstream slow" playbook.
5. **Is it locks?** `jvm_threads_states_threads{state="blocked"}`, then `jcmd Thread.print` (BLOCKED + `waiting to lock`),
   or JFR `jdk.JavaMonitorEnter` / `jdk.ThreadPark`.
6. Kafka is not on the synchronous checkout path (outbox), unless it is indirectly overloading the shared DB pool.

### High latency, CPU high → something is *computing*

1. Is it this JVM? Compare `process_cpu_usage` with `system_cpu_usage`: a neighbour process or GC threads?
2. GC: `rate(jvm_gc_pause_seconds_sum[1m])`, `jvm_gc_pause_seconds_max`, and the allocation rate
   `rate(jvm_gc_memory_allocated_bytes_total[1m])`. High allocation means many young GCs. Heap near max means
   long/full GCs.
3. Hot methods: a JFR recording (`JFR.start settings=profile` for 60 s, then `jfr print --events jdk.ExecutionSample`
   or JMC's method profiling). Example from this phase: `BCrypt.key`.
4. Exceptions as a cost: JFR `jdk.JavaExceptionThrow` (the EntityGraph finding above).
5. Executor saturation: `executor_active_threads == pool size` and `executor_queued_tasks` growing. More threads do not
   help a CPU-bound workload (lab: virtual threads give the same wall time).

### Memory continuously growing

1. **Heap *after* GC**, not heap used: `jvm_memory_usage_after_gc{area="heap",pool="long-lived"}`, or `GC.heap_info`
   right after a GC. A saw-tooth with a flat floor is normal.
2. **A rising floor.** `jcmd GC.class_histogram` twice, minutes apart, and diff the top classes (lab: `[B` from 12 MB to
   74 MB with 64 MiB retained).
3. **Usual suspects.**
   - Caches without eviction (lab: `ConcurrentHashMap` +42 MB vs LRU +2.1 MB).
   - Per-session/per-request maps.
   - `sessions_active` growing with flat logins.
   - payment-service's `InMemoryPaymentRepository`, which is a simulator that keeps every payment forever (by design,
     documented as a limitation).
4. **Who holds it:** a heap dump (with the caution above), then dominators in Eclipse MAT / JMC. JFR's `OldObjectSample`
   event (`settings=profile`) records the allocation site of objects that stay alive.
5. `OutOfMemoryError: Java heap space` with `-XX:+HeapDumpOnOutOfMemoryError` gives the dump at the moment of failure.

### DB pool exhausted

1. `hikaricp_connections_active == hikaricp_connections_max` with `pending > 0`, and `acquire_seconds_max` growing.
   `hikaricp_connections_timeout_total` > 0 means requests are failing (`SQLTransientConnectionException … request timed
   out`).
2. **Why are connections held so long?**
   - Slow queries: DB-side `pg_stat_activity`.
   - Long transactions: `marketplace.checkout.step.duration{step="place_order"}`.
   - Row-lock waits: `pg_locks`.
   - The outbox claim: `outbox_batch_duration_seconds`.
   - Or simply more concurrent work than connections.
3. **Is a remote call inside a transaction?** In this code base it cannot be: `PaymentClient.requireNoTransaction()`
   fails fast, and `PaymentClientTest.refusesToCallPaymentServiceInsideADatabaseTransaction` guards it.
   `marketplace.checkout.step.duration` shows the payment call as its own step, with no connection held.
4. **Measured in this phase.**
   - Browse load, 20 concurrent users, pool of 10: `hikaricp_connections_active` 10/10, `pending` up to 10,
     `acquire_seconds_max` 103 ms.
   - Cause: Spring Session JDBC reads the session from PostgreSQL on **every** authenticated request, and a cart read adds
     another query. The server-side-session design has a price, and it is paid in DB round-trips.
   - Options (not applied): a pool sized to measured concurrency, session caching or Redis sessions, fewer queries per
     request.

### Payment downstream slow

1. `payment_client_duration_seconds` p99 by `result`, and the counts `payment_client_calls_total{result="timeout"}`.
2. Checkout outcomes: PAYMENT_UNKNOWN grows (timeouts → reconciliation later), then PAYMENT_FAILED
   (`circuit_open`: fail fast, nothing charged).
3. `resilience4j_circuitbreaker_state{state="open"}`, `..._not_permitted_calls_total`, and the `dependencies` health
   (`paymentService: DEGRADED`).
4. Retry volume: `http_client_requests_seconds_count` (attempts) vs `payment_client_calls_total` (logical calls). The
   client retries only 502/503/504 and connect failures, never a read timeout.
5. Request threads: `tomcat_threads_busy_threads` and a thread dump (`PaymentClient.post` → `JdkClientHttpRequest`).
   Every slow call holds a request thread for up to the 2 s read timeout.
6. **Measured.**
   - SLOW for every checkout: the first 5 calls timed out at ~2.05 s (PAYMENT_UNKNOWN).
   - The circuit opened, and the next 11 checkouts failed fast (PAYMENT_FAILED, `circuit_open` p99 1 ms).
   - The thread dump showed 5 request threads parked in `JdkClientHttpRequest.executeInternal`.

### Kafka consumer behind

1. `kafka_consumer_fetch_manager_records_lag{topic="marketplace.order-events"}` per partition. One partition behind
   means **partition skew**: a hot key (one order) or a stuck record.
2. Processing latency: `order_events_processing_seconds` and `spring_kafka_listener_seconds` by `result`.
3. Retries: `order_events_retry_total`. With in-place retries the partition waits for the backoff (200/400/800 ms).
4. DLT: `order_events_dead_lettered_total{reason}` needs a human. The record is kept with diagnostic headers.
5. The projection DB: the order-activity `hikaricp_*` metrics and slow SQL.
6. Upstream: if the lag is 0 but events are missing, check the **outbox lag** in the marketplace (§7).

---

## 10. The production/JVM lab

Location: `marketplace-service/src/test/java/pl/dch/marketplace/productionlab` (tag `production-lab`) and
`tools/production-lab`. It is not part of the normal build:

```bash
cd marketplace-service
mvn test                                                   # normal suite: production-lab tests are excluded
mvn test -Pproduction-lab                                  # all experiments (~1-2 min)
mvn test -Pproduction-lab -Dtest=HikariPoolExhaustionLabTest
mvn test -Pproduction-lab -Dtest=LockContentionLabTest -Dlab.hold=60s    # keep the state; the PID is printed
#   → in another terminal: jcmd <pid> Thread.print | grep -A4 '"lab-blocked-1"'
mvn test -Pproduction-lab -Dtest=HeapRetentionLabTest -Dlab.retainMiB=256 -Dlab.hold=60s
#   → jcmd <pid> GC.class_histogram | head ; jcmd <pid> GC.heap_info
```

Every experiment prints `[PROD-LAB] …` lines. The assertions check the *shape* of the result (threads blocked,
connections pending, tasks rejected, bytes retained) with broad bounds. Observations from one run
(the full `-Pproduction-lab` run is recorded in `claude-result.md`):

| Experiment | Setup | Observed (final `-Pproduction-lab` run) |
|---|---|---|
| `HikariPoolExhaustionLabTest` | own pool of 3, 15 virtual threads × `pg_sleep(0.5)` | elapsed 2585 ms (5 waves), max active 3, max pending 14, acquire mean 1036 / max 2079 ms; with `connectionTimeout` 800 ms: 6 succeeded, 9 failed (`Connection is not available, request timed out after 810ms`), `hikaricp.connections.timeout` 9 |
| `HttpDownstreamLimitLabTest` | downstream capacity 4 × 100 ms, 200 requests | 4 callers: p50 109.2 / p99 180.2 ms, 36.0 req/s; 40 callers: p50 1091 / p99 1098 ms, **still 36.6 req/s**, 36 callers waiting (Micrometer's bucket estimate: p50 = p99 = 1137 ms) |
| `ExecutorSaturationLabTest` | 4 threads, burst of 200 × 20 ms | unbounded queue: 0 rejected, max queued 196, max wait 1520 ms; bounded(20)+Abort: 176 rejected, max wait 150 ms, done in 185 ms; bounded(20)+CallerRuns: 36 run by the caller, 0 rejected, submit loop throttled to 1113 ms |
| `LockContentionLabTest` | 1 monitor holder, 8 blocked, 2 parked on a ReentrantLock, 1 `Object.wait`, 1 sleeping | real states BLOCKED ×8 / WAITING / TIMED_WAITING; `ThreadMXBean`: lock owner `lab-lock-holder`; `Thread.print`: `BLOCKED (on object monitor)`, `- waiting to lock <0x…>` and `- locked <0x…>` with the same address, `WAITING (parking)` + `ReentrantLock$NonfairSync` |
| `VirtualThreadDiagnosticsLabTest` | 10,000 parked virtual threads; 1,000 tasks × 20 ms through 10 permits | started in 26 ms; platform thread count did not grow (91 → 77, other tests' threads ending); ~2.4 KB heap per virtual thread; `Thread.print` lists 16 carriers and none of the 10,000; `Thread.dump_to_file -format=json` (14.6 MB) lists 10,000; 10 permits: max concurrent 10, 3113 ms (theory 2000 ms + Windows timer granularity) |
| `CpuBoundLabTest` | 64 SHA-256 tasks on 16 cores | fixed pool 839 ms wall / 11.8 s CPU; virtual threads 937 ms / 12.1 s CPU; ratio 1.12 (another run: 0.98): no gain, same CPU |
| `HeapRetentionLabTest` | 64 × 1 MiB retained; 20,000 unique cache keys × 2 KiB | heap after GC 16.5 → 80.5 MiB; `GC.class_histogram` top `[B` 74.2 MiB; after release 17.1 MiB; unbounded `ConcurrentHashMap` +41.8 MiB vs LRU(1000) +2.1 MiB |
| `GcAndJfrLabTest` | 2 s of short-lived allocations, JFR `profile` | 18 young G1 collections (38 ms total), 0 old; ~9.9 GB/s allocated; JFR (629 KiB, 80 event types): 18 GarbageCollection (longest pause 4.4 ms), 584 ObjectAllocationSample, 120 ExecutionSample |
| `SlowPaymentDownstreamLabTest` | real `PaymentClient`, 8 callers, 300 ms read timeout, 200 calls | normal: mean 5.9, p99 63.6 ms; 5% slow: mean 17.5 ms (+11.6), p99 305.9 ms, 10 × `timeout`; all slow: 23 × `timeout`, then circuit OPEN and 177 × `circuit_open` in ~0.1 ms |
| `tools/production-lab/MemoryDemo.java retain` | `-Xmx64m -XX:+HeapDumpOnOutOfMemoryError` | OOM after retaining only **30 MiB**: 1 MiB arrays are G1 *humongous* objects (≥ half a region), and each wastes most of a second region, so they occupy far more than their size (heap dump 40 MB) |

### Memory vocabulary

- **High memory usage.** A large heap that is stable after GC. It may be fine (caches sized on purpose) or simply `-Xmx`
  not set: the JVM default is 1/4 of RAM, which on this laptop meant `MaxHeapSize=17 GB`.
- **Memory leak.** Objects that stay reachable although they are no longer needed: the heap floor rises after every GC
  until OOM.
- **GC pressure.** A high allocation rate, so many collections (CPU spent in GC, pauses). The heap after GC is flat and
  nothing leaks (`MemoryDemo churn`).
- **OOM.** An allocation failed after GC could not free enough space. The cause is a leak, a load spike, a too-small
  heap, or humongous-object fragmentation. Other kinds: `Metaspace`, `Direct buffer memory`,
  `unable to create native thread`.

### Load generator

`tools/production-lab/LoadGenerator.java` is a single file with no dependencies (`java File.java`). Every worker is a
real "browser": it registers its own user through the API, keeps the cookie, fetches the CSRF token and sends it on
unsafe requests. Options:
- `--scenario browse|cart|checkout`
- `--concurrency`, `--requests`, `--warmup`
- `--base-url`
- `--payment-scenario SLOW|DECLINED|… [--payment-scenario-every N]`

It reports requests, successes, failures, throughput and min/mean/p50/p95/p99/max per endpoint. It is a *closed loop*:
a worker waits for its response before sending the next request. When the server slows down, the generator slows down
too, so the measured tail understates what an open stream of real users would see (**coordinated omission**). Use it to
reproduce behaviour, not to publish numbers.

Measured live (20 concurrent browsers, 6000 requests after 1000 warm-up):

| | req/s | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| browse (GET products/product/cart) | 753.6 | 22.6 ms | 51.1 ms | 84.6 ms | 183.0 ms |
| cart mutations (10 browsers, 2000) | 464.3 | 20.9 ms | 30.7 ms | 36.3 ms | 49.4 ms |
| checkout, normal payment (5 browsers, 200) | 74.3 | 45.4 ms | 59.7 ms | 64.9 ms | 71.5 ms |

The checkout run also produced `STOCK_CONFLICT` 409s: 24 of 200 in this run, 67 of 200 in the baseline of §4. Five
shoppers buying the same six products race for the same stock rows, and optimistic locking turns the lost races into
`CONCURRENT_STOCK_CHANGE`. This is correct behaviour (nothing oversold), but it becomes visible as a rate under load
(`marketplace_stock_conflict_total`). Hot products under load are exactly where a reservation or queue design would be
discussed.

---

## 11. Production diagnosis sequence (summary)

1. **What does the user feel?** p99 and error rate per endpoint, not averages.
2. **Since when, and what changed?** A deploy, traffic, a dependency (compare the time axis across panels).
3. **Waiting or working?** CPU vs busy threads. Waiting → pools, downstreams, locks. Working → JFR CPU samples,
   allocation, GC.
4. **Which resource is saturated?** Threads, DB connections, downstream capacity, executor queues, heap. The saturated
   one has a queue: pending, queued, BLOCKED threads, waiting callers.
5. **Confirm with the JVM.** `jcmd Thread.print` (who waits for what), `GC.class_histogram` (what fills the heap), JFR
   (hot methods, exceptions, locks, I/O).
6. **Follow one request.** Take its trace id from the logs, open it in Jaeger, and see which hop took the time.
7. **Fix the bottleneck, then measure again.** The next one is usually right behind it.
