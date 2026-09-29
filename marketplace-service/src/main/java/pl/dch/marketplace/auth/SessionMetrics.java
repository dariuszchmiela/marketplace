package pl.dch.marketplace.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code sessions.active} — non-expired server-side sessions (Spring Session JDBC). A steadily growing value with a
 * flat login rate points at sessions not expiring (cleanup job not running) or at a client creating sessions in a
 * loop. Only a number: never session ids, user ids or emails.
 * <p>
 * The count uses the {@code EXPIRY_TIME} index, is capped, and is cached for {@code snapshotMaxAge}: scraping does
 * not query the session table on every request, and a huge table cannot make a scrape slow.
 */
@Component
class SessionMetrics {

    private static final Logger log = LoggerFactory.getLogger(SessionMetrics.class);
    private static final int COUNT_LIMIT = 1_000_000;

    private final JdbcTemplate jdbcTemplate;
    private final Duration snapshotMaxAge;
    private final Clock clock = Clock.systemUTC();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    private record Snapshot(double activeSessions, Instant takenAt) {
    }

    SessionMetrics(JdbcTemplate jdbcTemplate, MeterRegistry registry,
                   @Value("${app.metrics.session-snapshot-max-age:15s}") Duration snapshotMaxAge) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotMaxAge = snapshotMaxAge;
        Gauge.builder("sessions.active", this, SessionMetrics::activeSessions)
                .description("Non-expired server-side sessions (Spring Session JDBC)")
                .register(registry);
    }

    double activeSessions() {
        Snapshot current = snapshot.get();
        Instant now = clock.instant();
        if (current == null || current.takenAt().plus(snapshotMaxAge).isBefore(now)) {
            try {
                Long count = jdbcTemplate.queryForObject("""
                        select count(*) from (select 1 from spring_session where expiry_time > ? limit ?) active""",
                        Long.class, now.toEpochMilli(), COUNT_LIMIT);
                current = new Snapshot(count == null ? 0 : count, now);
                snapshot.set(current);
            } catch (RuntimeException ex) {
                log.warn("sessions.count_failed error=\"{}\"", ex.getMessage());
                return Double.NaN;
            }
        }
        return current.activeSessions();
    }
}
