package pl.dch.marketplace.productionlab;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: DB connection pool exhaustion, observed through the same HikariCP metrics the application exports
 * ({@code hikaricp_connections_active / idle / pending / max}, {@code hikaricp_connections_acquire_seconds}).
 * <p>
 * A dedicated pool of 3 connections (never the application's pool) and 15 concurrent tasks that each hold a
 * connection for 500 ms ({@code pg_sleep}). Lesson: many (virtual) threads != many DB connections — the pool is the
 * concurrency limit, the remaining threads queue for a connection, and the queue shows up as {@code pending > 0} and a
 * growing acquire time long before anything fails. With a short {@code connectionTimeout} the queue turns into errors.
 */
@Tag(ProductionLab.TAG)
class HikariPoolExhaustionLabTest {

    private static final int POOL_SIZE = 3;
    private static final int TASKS = 15;
    private static final Duration HOLD = Duration.ofMillis(500);

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    @Test
    void moreTasksThanConnectionsQueueForTheConnection() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (HikariDataSource pool = pool(registry, Duration.ofSeconds(30))) {
            Sampled sampled = runConcurrently(pool, TASKS);

            Timer acquire = registry.get("hikaricp.connections.acquire").timer();
            ProductionLab.print("pool " + POOL_SIZE + ", " + TASKS + " virtual threads x " + HOLD.toMillis() + " ms",
                    "elapsed " + sampled.elapsed.toMillis() + " ms, max active " + sampled.maxActive + ", max pending "
                            + sampled.maxPending + ", errors " + sampled.errors);
            ProductionLab.print("hikaricp.connections.acquire",
                    "count " + acquire.count() + ", mean " + String.format(Locale.ROOT, "%.0f", acquire.mean(TimeUnit.MILLISECONDS))
                            + " ms, max " + String.format(Locale.ROOT, "%.0f", acquire.max(TimeUnit.MILLISECONDS)) + " ms");
            ProductionLab.print("hikaricp.connections.max / pending (gauges)",
                    registry.get("hikaricp.connections.max").gauge().value() + " / "
                            + registry.get("hikaricp.connections.pending").gauge().value() + " after the run");

            assertThat(sampled.maxActive).isEqualTo(POOL_SIZE);                  // never more than the pool
            assertThat(sampled.maxPending).isGreaterThanOrEqualTo(TASKS - POOL_SIZE - 2);   // the rest waited
            assertThat(sampled.errors).isZero();                                  // long timeout: slow, not failing
            // 15 tasks / 3 connections = 5 "waves" of 500 ms.
            assertThat(sampled.elapsed).isGreaterThanOrEqualTo(HOLD.multipliedBy(TASKS / POOL_SIZE).minusMillis(100));
            assertThat(acquire.max(TimeUnit.MILLISECONDS)).isGreaterThan(HOLD.multipliedBy(3).toMillis());
        }
    }

    @Test
    void withAShortConnectionTimeoutTheQueueBecomesErrors() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (HikariDataSource pool = pool(registry, Duration.ofMillis(800))) {
            Sampled sampled = runConcurrently(pool, TASKS);

            double timeouts = registry.get("hikaricp.connections.timeout").counter().count();
            ProductionLab.print("same load, connectionTimeout 800 ms",
                    "elapsed " + sampled.elapsed.toMillis() + " ms, succeeded " + (TASKS - sampled.errors) + ", failed "
                            + sampled.errors + " (" + sampled.firstError + "), hikaricp.connections.timeout " + timeouts);

            assertThat(sampled.errors).isPositive();
            assertThat(timeouts).isEqualTo(sampled.errors);
        }
    }

    private HikariDataSource pool(SimpleMeterRegistry registry, Duration connectionTimeout) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setPoolName("lab-pool");
        config.setMaximumPoolSize(POOL_SIZE);
        config.setMinimumIdle(POOL_SIZE);
        config.setConnectionTimeout(connectionTimeout.toMillis());
        config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
        return new HikariDataSource(config);
    }

    private record Sampled(Duration elapsed, int maxActive, int maxPending, int errors, String firstError) {
    }

    private Sampled runConcurrently(HikariDataSource pool, int tasks) throws Exception {
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger maxPending = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        ConcurrentLinkedQueue<String> errorMessages = new ConcurrentLinkedQueue<>();
        AtomicBoolean running = new AtomicBoolean(true);

        // What a metrics scrape would see, sampled continuously (Prometheus would see it every 15 s).
        Thread sampler = Thread.ofPlatform().name("lab-hikari-sampler").start(() -> {
            while (running.get()) {
                var mx = pool.getHikariPoolMXBean();
                maxActive.accumulateAndGet(mx.getActiveConnections(), Math::max);
                maxPending.accumulateAndGet(mx.getThreadsAwaitingConnection(), Math::max);
                ProductionLab.sleep(Duration.ofMillis(5));
            }
        });
        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(executor.submit(() -> {
                    try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
                        statement.execute("select pg_sleep(" + HOLD.toMillis() / 1000.0 + ")");
                    } catch (SQLTransientConnectionException ex) {
                        errors.incrementAndGet();
                        errorMessages.add(ex.getMessage());
                    } catch (SQLException ex) {
                        throw new IllegalStateException(ex);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            running.set(false);
            sampler.join();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        String firstError = errorMessages.isEmpty() ? "-" : errorMessages.peek().replaceAll("\\s+", " ");
        return new Sampled(elapsed, maxActive.get(), maxPending.get(), errors.get(),
                firstError.length() > 90 ? firstError.substring(0, 90) + "..." : firstError);
    }
}
