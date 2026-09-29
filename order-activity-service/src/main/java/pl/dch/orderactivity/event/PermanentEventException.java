package pl.dch.orderactivity.event;

/**
 * An event that can never be processed successfully, however often it is redelivered: malformed JSON, missing
 * required fields, an unsupported schema version or type, an impossible state transition. It is <strong>not</strong>
 * retried; it goes straight to the dead-letter topic for manual inspection. Every other exception is treated as
 * transient (retried with backoff, then dead-lettered).
 */
public class PermanentEventException extends RuntimeException {

    public PermanentEventException(String message) {
        super(message);
    }

    public PermanentEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class MalformedEventException extends PermanentEventException {

        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class UnsupportedEventException extends PermanentEventException {

        public UnsupportedEventException(String message) {
            super(message);
        }
    }

    public static class InvalidStateTransitionException extends PermanentEventException {

        public InvalidStateTransitionException(String message) {
            super(message);
        }
    }
}
