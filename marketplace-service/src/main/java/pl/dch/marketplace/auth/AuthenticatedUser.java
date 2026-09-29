package pl.dch.marketplace.auth;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;
import pl.dch.marketplace.session.SessionId;

/**
 * The authenticated principal kept in the server-side session (Spring Session JDBC serializes it, hence
 * {@link Serializable}). It holds only immutable identifiers, so no user lookup is needed per request, and no
 * password hash. {@link #getName()} is the user id: it ends up in {@code SPRING_SESSION.PRINCIPAL_NAME} and in
 * logs, where the email (PII) should not.
 */
public record AuthenticatedUser(long userId, String email, UUID shoppingSessionId)
        implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public AuthenticatedUser {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(shoppingSessionId, "shoppingSessionId must not be null");
    }

    /** The owner key of this user's carts and orders. The browser never sends or sees it. */
    public SessionId sessionId() {
        return new SessionId(shoppingSessionId);
    }

    @Override
    public String getName() {
        return Long.toString(userId);
    }

    /** Keeps the email out of accidental log output (toString of the principal). */
    @Override
    public String toString() {
        return "AuthenticatedUser[userId=" + userId + "]";
    }
}
