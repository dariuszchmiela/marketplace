package pl.dch.marketplace.lab;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Lab only. A deterministic downstream service on a local port: every request takes a fixed latency
 * (blocking I/O on the server side), optionally behind a concurrency limit — like a service with a fixed
 * number of workers or a rate limit. Requests above the limit wait in line.
 * <p>
 * Records how many requests were being <em>processed</em> at the same time ({@link #maxInProgress()}),
 * which is what a client cannot increase by adding threads.
 */
public final class FakeDownstream implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService handlerThreads = Executors.newCachedThreadPool();
    private final Map<String, Duration> latencyByPathPrefix = new ConcurrentHashMap<>();
    private final Set<String> failingPathPrefixes = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inProgress = new AtomicInteger();
    private final AtomicInteger maxInProgress = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger maxWaiting = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Duration latency;
    private volatile Semaphore capacity;

    private FakeDownstream(HttpServer server, Duration latency) {
        this.server = server;
        this.latency = latency;
    }

    public static FakeDownstream start(Duration latency) {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 500);
            FakeDownstream downstream = new FakeDownstream(httpServer, latency);
            httpServer.createContext("/", downstream::handle);
            httpServer.setExecutor(downstream.handlerThreads);
            httpServer.start();
            return downstream;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** At most {@code maxConcurrentRequests} requests are processed at once; the rest wait. */
    public void limitConcurrency(int maxConcurrentRequests) {
        this.capacity = new Semaphore(maxConcurrentRequests, true);
    }

    public void unlimited() {
        this.capacity = null;
    }

    /** Requests whose path starts with the prefix fail immediately with HTTP 500. */
    public void fail(String pathPrefix) {
        failingPathPrefixes.add(pathPrefix);
    }

    public void latency(String pathPrefix, Duration pathLatency) {
        latencyByPathPrefix.put(pathPrefix, pathLatency);
    }

    public void reset(Duration defaultLatency) {
        latency = defaultLatency;
        capacity = null;
        latencyByPathPrefix.clear();
        failingPathPrefixes.clear();
        inProgress.set(0);
        maxInProgress.set(0);
        waiting.set(0);
        maxWaiting.set(0);
        requests.set(0);
    }

    public int maxInProgress() {
        return maxInProgress.get();
    }

    public int maxWaiting() {
        return maxWaiting.get();
    }

    public int requests() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
        handlerThreads.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            if (failingPathPrefixes.stream().anyMatch(path::startsWith)) {
                send(exchange, 500, "failed: " + path);
                return;
            }
            Semaphore limit = capacity;
            if (limit != null) {
                maxWaiting.accumulateAndGet(waiting.incrementAndGet(), Math::max);
                limit.acquireUninterruptibly();
                waiting.decrementAndGet();
            }
            try {
                maxInProgress.accumulateAndGet(inProgress.incrementAndGet(), Math::max);
                Thread.sleep(latencyFor(path));
                send(exchange, 200, "value-of:" + path);
            } finally {
                inProgress.decrementAndGet();
                if (limit != null) {
                    limit.release();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            // client went away (timeout/cancellation)
        }
    }

    private Duration latencyFor(String path) {
        return latencyByPathPrefix.entrySet().stream()
                .filter(entry -> path.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(latency);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
