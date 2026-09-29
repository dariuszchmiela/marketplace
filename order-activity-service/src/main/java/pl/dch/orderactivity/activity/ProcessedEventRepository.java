package pl.dch.orderactivity.activity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.dch.orderactivity.event.OrderEvent;

@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Claims the event id for the current transaction. {@code false} = already processed (duplicate delivery).
     * If two consumers process the same event at the same time, the second INSERT waits for the first transaction
     * on the primary key and then finds the row: the effect still happens only once.
     */
    public boolean markProcessed(OrderEvent event) {
        return jdbcTemplate.update("""
                        insert into processed_event (event_id, event_type, order_id) values (?, ?, ?)
                        on conflict (event_id) do nothing""",
                event.eventId(), event.eventType(), event.orderId()) == 1;
    }
}
