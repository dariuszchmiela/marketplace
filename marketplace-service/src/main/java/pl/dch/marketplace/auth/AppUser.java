package pl.dch.marketplace.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A registered shopper. Never leaves the service: API responses use {@link AuthController.UserResponse},
 * the session stores {@link AuthenticatedUser}. There is deliberately no getter-based DTO mapping of the hash.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /** Owner key of carts and orders; generated once, stable across logins, never sent to the browser. */
    @Column(name = "shopping_session_id", nullable = false, updatable = false)
    private UUID shoppingSessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {
        // for JPA
    }

    AppUser(String normalizedEmail, String passwordHash, Instant createdAt) {
        this.email = Objects.requireNonNull(normalizedEmail, "email must not be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        this.shoppingSessionId = UUID.randomUUID();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    AuthenticatedUser toAuthenticatedUser() {
        return new AuthenticatedUser(id, email, shoppingSessionId);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    public UUID getShoppingSessionId() {
        return shoppingSessionId;
    }
}
