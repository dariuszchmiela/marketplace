package pl.dch.marketplace.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import pl.dch.marketplace.IntegrationTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab: 30 virtual threads each running a 200 ms query against the application's real connection pool
 * (HikariCP, default maximum 10 connections). The pool, not the number of threads, decides how many
 * queries run at the same time; the rest of the virtual threads wait for a connection.
 */
class DatabasePoolLimitLabTest extends IntegrationTestBase {

    @Test
    void virtualThreadsDoNotGetMoreDatabaseConnectionsThanThePoolHas() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        int poolSize = pool.getMaximumPoolSize();
        int tasks = 30;
        AtomicInteger maxActiveConnections = new AtomicInteger();
        AtomicInteger maxThreadsWaitingForConnection = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);

        Thread sampler = Thread.ofPlatform().start(() -> {
            while (running.get()) {
                maxActiveConnections.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(), Math::max);
                maxThreadsWaitingForConnection.accumulateAndGet(
                        pool.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
                Thread.onSpinWait();
            }
        });
        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(executor.submit(() -> jdbcTemplate.execute("select pg_sleep(0.2)")));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            running.set(false);
            sampler.join();
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        LabReport.print("30 virtual threads x 200 ms query, pool " + poolSize,
                elapsed + " ms, max active connections " + maxActiveConnections.get()
                        + ", max threads waiting for a connection " + maxThreadsWaitingForConnection.get());

        assertThat(maxActiveConnections.get()).isLessThanOrEqualTo(poolSize);
        assertThat(maxThreadsWaitingForConnection.get()).isGreaterThanOrEqualTo(tasks - poolSize - 2);
        // At least ceil(30 / 10) = 3 rounds of 200 ms.
        assertThat(elapsed).isGreaterThanOrEqualTo(((tasks + poolSize - 1) / poolSize) * 200L);
    }
}
