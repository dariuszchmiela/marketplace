package pl.dch.orderactivity.activity;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.dch.orderactivity.event.OrderEvent;

@Repository
public class OrderActivityRepository {

    public record Activity(long orderId, String currentStatus, BigDecimal total, String currency, UUID lastEventId,
                           int lastSequence, Instant updatedAt) {
    }

    public record Entry(UUID eventId, String eventType, int sequence, String status, String message, Instant occurredAt) {
    }

    private final JdbcTemplate jdbcTemplate;

    public OrderActivityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Locked: two events of the same order are not applied at the same time (e.g. after a rebalance). */
    public Optional<Activity> findForUpdate(long orderId) {
        return jdbcTemplate.query("select * from order_activity where order_id = ? for update", (rs, rowNum) ->
                new Activity(rs.getLong("order_id"), rs.getString("current_status"), rs.getBigDecimal("total"),
                        rs.getString("currency"), rs.getObject("last_event_id", UUID.class), rs.getInt("last_sequence"),
                        rs.getTimestamp("updated_at").toInstant()), orderId).stream().findFirst();
    }

    public Optional<Activity> find(long orderId) {
        return jdbcTemplate.query("select * from order_activity where order_id = ?", (rs, rowNum) ->
                new Activity(rs.getLong("order_id"), rs.getString("current_status"), rs.getBigDecimal("total"),
                        rs.getString("currency"), rs.getObject("last_event_id", UUID.class), rs.getInt("last_sequence"),
                        rs.getTimestamp("updated_at").toInstant()), orderId).stream().findFirst();
    }

    public void insert(OrderEvent event) {
        jdbcTemplate.update("""
                        insert into order_activity (order_id, current_status, total, currency, last_event_id, last_sequence, updated_at)
                        values (?, ?, ?, ?, ?, ?, now())""",
                event.orderId(), event.status(), event.total(), event.currency(), event.eventId(), event.sequence());
    }

    public void update(OrderEvent event) {
        jdbcTemplate.update("""
                        update order_activity set current_status = ?, total = ?, last_event_id = ?, last_sequence = ?, updated_at = now()
                        where order_id = ?""",
                event.status(), event.total(), event.eventId(), event.sequence(), event.orderId());
    }

    public void addEntry(OrderEvent event, String message) {
        jdbcTemplate.update("""
                        insert into order_activity_entry (order_id, event_id, event_type, sequence, status, message, occurred_at)
                        values (?, ?, ?, ?, ?, ?, ?)""",
                event.orderId(), event.eventId(), event.eventType(), event.sequence(), event.status(), message,
                Timestamp.from(event.occurredAt()));
    }

    public List<Entry> history(long orderId) {
        return jdbcTemplate.query("""
                        select event_id, event_type, sequence, status, message, occurred_at from order_activity_entry
                        where order_id = ? order by id""",
                (rs, rowNum) -> new Entry(rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        rs.getInt("sequence"), rs.getString("status"), rs.getString("message"),
                        rs.getTimestamp("occurred_at").toInstant()), orderId);
    }
}
