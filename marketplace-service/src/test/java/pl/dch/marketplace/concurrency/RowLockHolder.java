package pl.dch.marketplace.concurrency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test tool for deterministic race tests without any hooks or sleeps in production code.
 * <p>
 * Holds row locks in its own plain JDBC transaction (not bound to any thread). While it holds the lock,
 * application transactions can still <em>read</em> the row (PostgreSQL MVCC) but queue as soon as they
 * <em>write</em> or lock it. {@link #awaitLockWaiters} waits until the expected number of transactions
 * are queued, i.e. all of them have passed the read and reached the conflicting write. Closing the
 * holder (rollback, nothing changed) lets them continue.
 */
public final class RowLockHolder implements AutoCloseable {

    private final Connection connection;

    private RowLockHolder(Connection connection) {
        this.connection = connection;
    }

    /** Runs a {@code SELECT … FOR UPDATE} (or any locking statement) and keeps its transaction open. */
    public static RowLockHolder lock(DataSource dataSource, String lockingSql, Object... arguments) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(lockingSql)) {
                for (int i = 0; i < arguments.length; i++) {
                    statement.setObject(i + 1, arguments[i]);
                }
                statement.execute();
            }
            return new RowLockHolder(connection);
        } catch (SQLException ex) {
            if (connection != null) {
                new RowLockHolder(connection).close();
            }
            throw new IllegalStateException("Could not take the lock", ex);
        }
    }

    /** Waits until at least {@code count} database sessions are blocked waiting for a lock. */
    public static void awaitLockWaiters(JdbcTemplate jdbcTemplate, int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Expected " + count + " transactions waiting for a lock");
    }

    @Override
    public void close() {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
            connection.close();
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not release the lock", ex);
        }
    }
}
