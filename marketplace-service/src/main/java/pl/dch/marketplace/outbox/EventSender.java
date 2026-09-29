package pl.dch.marketplace.outbox;

/**
 * The publishing abstraction used by {@link OutboxPublisher}. Returns only after the broker acknowledged the
 * record; throws {@link EventPublicationException} otherwise.
 */
public interface EventSender {

    void send(OutboxRecord record);

    class EventPublicationException extends RuntimeException {

        public EventPublicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
