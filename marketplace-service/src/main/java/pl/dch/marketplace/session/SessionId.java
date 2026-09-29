package pl.dch.marketplace.session;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies the owner of a cart and of orders (the {@code session_id} column of those tables).
 * <p>
 * Since Phase 5 it is the authenticated user's {@code app_user.shopping_session_id}, resolved from the principal by
 * {@link SessionIdArgumentResolver}. (Phases 1–4 let the browser choose it via an {@code X-Session-Id} header; that
 * header no longer exists.) Controllers and services only depend on this type, so the switch to real authentication
 * changed the resolver, not the endpoints.
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
