package pl.dch.marketplace.outbox;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC access to {@code outbox_event}: the SQL is the interesting part here, so it is written out.
 * JdbcTemplate joins the current JPA transaction (same connection), so an outbox INSERT commits or rolls back
 * together with the entity changes of that transaction.
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Callers change one aggregate at a time under its row lock (see OrderPaymentUpdater), so MAX + 1 is
     * race-free; the unique (aggregate_type, aggregate_id, sequence) constraint is the last guard.
     */
    public int nextSequence(String aggregateType, String aggregateId) {
        Integer max = jdbcTemplate.queryForObject(
                "select coalesce(max(sequence), 0) from outbox_event where aggregate_type = ? and aggregate_id = ?",
                Integer.class, aggregateType, aggregateId);
        return (max == null ? 0 : max) + 1;
    }

    public void insert(UUID eventId, String aggregateType, String aggregateId, String eventType, int schemaVersion,
                       int sequence, String payload, Instant occurredAt) {
        jdbcTemplate.update("""
                        insert into outbox_event (event_id, aggregate_type, aggregate_id, event_type, schema_version,
                                                  sequence, payload, occurred_at)
                        values (?, ?, ?, ?, ?, ?, ?::json, ?)""",
                eventId, aggregateType, aggregateId, eventType, schemaVersion, sequence, payload, Timestamp.from(occurredAt));
    }

    /**
     * Claims up to {@code limit} publishable rows for the current transaction.
     * <ul>
     *   <li>{@code FOR UPDATE SKIP LOCKED}: rows claimed by another publisher instance are skipped, not waited
     *       for — publishers work in parallel on different rows, nobody is globally serialized;</li>
     *   <li>head of line per aggregate ({@code NOT EXISTS} an earlier unpublished row of the same aggregate):
     *       only the oldest unpublished event of an order is eligible. Even with several publishers, OrderPaid can
     *       never overtake OrderCreated of the same order; a failing event blocks only its own order;</li>
     *   <li>{@code next_attempt_at}: rows that failed recently wait for their backoff.</li>
     * </ul>
     */
    public List<OutboxRecord> claimBatch(int limit) {
        return jdbcTemplate.query("""
                        select o.id, o.event_id, o.aggregate_type, o.aggregate_id, o.event_type, o.schema_version,
                               o.sequence, o.payload::text as payload, o.attempt_count
                        from outbox_event o
                        where o.published_at is null
                          and (o.next_attempt_at is null or o.next_attempt_at <= clock_timestamp())
                          and not exists (select 1
                                          from outbox_event earlier
                                          where earlier.aggregate_type = o.aggregate_type
                                            and earlier.aggregate_id = o.aggregate_id
                                            and earlier.published_at is null
                                            and earlier.id < o.id)
                        order by o.id
                        limit ?
                        for update of o skip locked""",
                (rs, rowNum) -> new OutboxRecord(
                        rs.getLong("id"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getInt("schema_version"),
                        rs.getInt("sequence"),
                        rs.getString("payload"),
                        rs.getInt("attempt_count")),
                limit);
    }

    public void markPublished(long id) {
        jdbcTemplate.update("""
                update outbox_event
                set published_at = clock_timestamp(), last_attempt_at = clock_timestamp(),
                    attempt_count = attempt_count + 1, last_error = null, next_attempt_at = null
                where id = ?""", id);
    }

    public void markFailed(long id, String error, Duration retryAfter) {
        jdbcTemplate.update("""
                update outbox_event
                set attempt_count = attempt_count + 1, last_attempt_at = clock_timestamp(), last_error = ?,
                    next_attempt_at = clock_timestamp() + (? * interval '1 millisecond')
                where id = ?""", truncate(error), retryAfter.toMillis(), id);
    }

    private static String truncate(String error) {
        return error == null || error.length() <= 500 ? error : error.substring(0, 500);
    }
}
