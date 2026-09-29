package pl.dch.marketplace.payment;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A programmable stand-in for payment-service on a real local port (JDK {@link HttpServer}, no extra
 * dependency). Real sockets matter here: timeouts, retries and concurrency are exactly what is tested.
 * <p>
 * POST behaviours are consumed in order; the last one repeats. Payments recorded by POSTs are
 * idempotent by key and are returned by {@code GET /api/payments/by-idempotency-key/{key}}.
 */
public final class FakePaymentServer implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record RecordedRequest(String method, String path, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String idempotencyKey() {
            return json().get("idempotencyKey").asString();
        }
    }

    /** What to do with one POST. */
    public sealed interface Behaviour {
    }

    private record Record(String status, Duration responseDelay) implements Behaviour {
    }

    private record Fail(int status) implements Behaviour {
    }

    private record StoredPayment(String paymentId, long orderId, BigDecimal amount, String currency,
                                 String idempotencyKey, String status) {
    }

    public static Behaviour succeed() {
        return new Record("SUCCEEDED", Duration.ZERO);
    }

    public static Behaviour decline() {
        return new Record("DECLINED", Duration.ZERO);
    }

    /** Responds with the given status without recording a payment. */
    public static Behaviour fail(int status) {
        return new Fail(status);
    }

    /** Records a successful payment, then waits before responding: the lost-response scenario. */
    public static Behaviour succeedButRespondAfter(Duration delay) {
        return new Record("SUCCEEDED", delay);
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, StoredPayment> paymentsByKey = new ConcurrentHashMap<>();
    private final List<Behaviour> postBehaviours = new ArrayList<>();
    private volatile int postCount;
    private volatile Integer lookupFailureStatus;
    private volatile Consumer<RecordedRequest> beforePostHandling = request -> { };

    private FakePaymentServer(HttpServer server) {
        this.server = server;
    }

    public static FakePaymentServer start() {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakePaymentServer fake = new FakePaymentServer(httpServer);
            httpServer.createContext("/api/payments", fake::handle);
            httpServer.setExecutor(fake.executor);
            httpServer.start();
            fake.reset();
            return fake;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public synchronized void reset() {
        requests.clear();
        paymentsByKey.clear();
        postBehaviours.clear();
        postBehaviours.add(succeed());
        postCount = 0;
        lookupFailureStatus = null;
        beforePostHandling = request -> { };
    }

    public synchronized void respondWith(Behaviour... behaviours) {
        postBehaviours.clear();
        postBehaviours.addAll(List.of(behaviours));
    }

    /** Runs on the server thread while the marketplace waits for the POST response. */
    public void beforePostHandling(Consumer<RecordedRequest> hook) {
        this.beforePostHandling = hook;
    }

    public void failLookupsWith(int status) {
        this.lookupFailureStatus = status;
    }

    /** Pre-records a payment, e.g. one that does not match the order. */
    public void storePayment(String idempotencyKey, long orderId, BigDecimal amount, String status) {
        paymentsByKey.put(idempotencyKey, new StoredPayment(UUID.randomUUID().toString(), orderId, amount, "PLN",
                idempotencyKey, status));
    }

    public List<RecordedRequest> postRequests() {
        return requests.stream().filter(request -> request.method().equals("POST")).toList();
    }

    public List<RecordedRequest> lookupRequests() {
        return requests.stream().filter(request -> request.method().equals("GET")).toList();
    }

    public int paymentCount() {
        return paymentsByKey.size();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            RecordedRequest request = record(exchange);
            if (request.method().equals("POST") && request.path().equals("/api/payments")) {
                handlePost(exchange, request);
            } else if (request.method().equals("GET") && request.path().startsWith("/api/payments/by-idempotency-key/")) {
                handleLookup(exchange, request.path().substring("/api/payments/by-idempotency-key/".length()));
            } else {
                send(exchange, 404, "{\"code\":\"NOT_FOUND\"}");
            }
        } catch (IOException ex) {
            // The client gave up (timeout) and closed the connection; expected in timeout tests.
        }
    }

    private void handlePost(HttpExchange exchange, RecordedRequest request) throws IOException {
        beforePostHandling.accept(request);
        switch (nextPostBehaviour()) {
            case Fail fail -> send(exchange, fail.status(), "{\"code\":\"SIMULATED\",\"message\":\"failure\"}");
            case Record record -> {
                JsonNode json = request.json();
                String key = json.get("idempotencyKey").asString();
                StoredPayment payment = paymentsByKey.computeIfAbsent(key, ignored -> new StoredPayment(
                        UUID.randomUUID().toString(), json.get("orderId").asLong(), json.get("amount").decimalValue(),
                        json.get("currency").asString(), key, record.status()));
                sleep(record.responseDelay());
                send(exchange, 201, toJson(payment));
            }
        }
    }

    private void handleLookup(HttpExchange exchange, String key) throws IOException {
        Integer failure = lookupFailureStatus;
        if (failure != null) {
            send(exchange, failure, "{\"code\":\"SIMULATED\"}");
            return;
        }
        StoredPayment payment = paymentsByKey.get(key);
        if (payment == null) {
            send(exchange, 404, "{\"code\":\"PAYMENT_NOT_FOUND\"}");
        } else {
            send(exchange, 200, toJson(payment));
        }
    }

    private synchronized Behaviour nextPostBehaviour() {
        int index = Math.min(postCount++, postBehaviours.size() - 1);
        return postBehaviours.get(index);
    }

    private RecordedRequest record(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RecordedRequest request = new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                headers, body);
        requests.add(request);
        return request;
    }

    private static String toJson(StoredPayment payment) {
        return """
                {"paymentId":"%s","orderId":%d,"status":"%s","amount":%s,"currency":"%s","idempotencyKey":"%s","createdAt":"%s"}"""
                .formatted(payment.paymentId(), payment.orderId(), payment.status(), payment.amount().toPlainString(),
                        payment.currency(), payment.idempotencyKey(), Instant.now());
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
