package pl.dch.marketplace.lab;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import pl.dch.marketplace.lab.DownstreamClient.DownstreamException;

/**
 * Lab only. One workload — a product page composed from three <em>independent</em> blocking downstream
 * calls (price, stock, delivery estimate) — implemented three ways. The calls and their latency are
 * identical in all variants; only the way threads wait for them differs.
 */
public final class ProductPageLoader {

    /** One downstream result plus the thread that waited for it (to show where the blocking happened). */
    public record Part(String value, String threadName, boolean virtual) {
    }

    public record ProductPage(Part price, Part stock, Part delivery) {

        public List<Part> parts() {
            return List.of(price, stock, delivery);
        }
    }

    public static class ProductPageTimeoutException extends RuntimeException {

        public ProductPageTimeoutException(Duration timeout) {
            super("Product page not complete within " + timeout);
        }
    }

    private final DownstreamClient client;

    public ProductPageLoader(DownstreamClient client) {
        this.client = client;
    }

    /**
     * The explicit executor for the {@code CompletableFuture} variant: a bounded pool of <em>platform</em>
     * threads with recognisable names, dedicated to blocking I/O. Never the implicit
     * {@code ForkJoinPool.commonPool()}, which is sized for CPU work (cores - 1) and shared by the whole JVM.
     */
    public static ExecutorService newIoExecutor(int threads) {
        return Executors.newFixedThreadPool(threads, Thread.ofPlatform().name("product-page-io-", 1).factory());
    }

    /** A: one call after another. Latency = sum of all calls. */
    public ProductPage loadSequentially(long productId) {
        return new ProductPage(call("/price/" + productId), call("/stock/" + productId), call("/delivery/" + productId));
    }

    /**
     * B: the three calls run concurrently on the given executor. Latency ≈ the slowest call.
     * <p>
     * Error handling is <strong>fail-fast</strong>: the page fails as soon as any call fails (or the timeout
     * expires) — {@code allOf} alone would wait for the slowest call even after one already failed. The
     * remaining futures are then cancelled, but note: {@code CompletableFuture.cancel} does <em>not</em>
     * interrupt the thread; the pool thread stays blocked in its HTTP call until it returns or times out.
     * The only place this method blocks is the single {@code join()} on the combined future.
     */
    public ProductPage loadWithCompletableFuture(long productId, ExecutorService executor, Duration timeout) {
        CompletableFuture<Part> price = CompletableFuture.supplyAsync(() -> call("/price/" + productId), executor);
        CompletableFuture<Part> stock = CompletableFuture.supplyAsync(() -> call("/stock/" + productId), executor);
        CompletableFuture<Part> delivery = CompletableFuture.supplyAsync(() -> call("/delivery/" + productId), executor);
        List<CompletableFuture<Part>> calls = List.of(price, stock, delivery);

        CompletableFuture<Void> firstFailure = new CompletableFuture<>();
        calls.forEach(call -> call.whenComplete((part, failure) -> {
            if (failure != null) {
                firstFailure.completeExceptionally(failure);
            }
        }));
        CompletableFuture<ProductPage> page = CompletableFuture.anyOf(CompletableFuture.allOf(price, stock, delivery), firstFailure)
                // All three are complete here, so these join() calls do not block.
                .thenApply(ignored -> new ProductPage(price.join(), stock.join(), delivery.join()))
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return page.join();
        } catch (CompletionException | CancellationException ex) {
            calls.forEach(call -> call.cancel(true));
            throw unwrap(ex, timeout);
        }
    }

    /**
     * C: the same blocking calls, each on its own virtual thread. The code stays plain and blocking
     * ({@code Future.get()}); while a virtual thread waits for I/O it does not occupy a platform thread.
     * <p>
     * Error handling is deliberately the simple structured one: the method never returns while one of its
     * tasks is still running ({@code close()} of the executor waits for all of them). A failure is therefore
     * reported only after the other in-flight calls finished — not fail-fast. Java 25's
     * {@code StructuredTaskScope} (still a preview API) adds fail-fast + automatic cancellation to this model.
     */
    public ProductPage loadWithVirtualThreads(long productId) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Part> price = executor.submit(() -> call("/price/" + productId));
            Future<Part> stock = executor.submit(() -> call("/stock/" + productId));
            Future<Part> delivery = executor.submit(() -> call("/delivery/" + productId));
            return new ProductPage(await(price), await(stock), await(delivery));
        }
    }

    private Part call(String path) {
        String value = client.get(path);
        Thread thread = Thread.currentThread();
        return new Part(value, thread.getName(), thread.isVirtual());
    }

    private static Part await(Future<Part> future) {
        try {
            return future.get();
        } catch (ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", ex);
        }
    }

    private static RuntimeException unwrap(RuntimeException wrapper, Duration timeout) {
        Throwable cause = wrapper;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return new ProductPageTimeoutException(timeout);
        }
        if (cause instanceof DownstreamException downstream) {
            return downstream;
        }
        return cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
    }
}
