package pl.dch.marketplace.session;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies the anonymous shopper who owns a cart and orders.
 * <p>
 * Phase 1 has no authentication: the browser generates a UUID, keeps it in local storage
 * and sends it as the {@value SessionIdArgumentResolver#HEADER_NAME} header. Controllers only
 * depend on this type, so replacing the header with a real authenticated identity later
 * means changing {@link SessionIdArgumentResolver} rather than every endpoint.
 */
public record SessionId(UUID value) {

    public SessionId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static SessionId of(String value) {
        return new SessionId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
