package pl.dch.marketplace.auth;

import java.util.Locale;

/**
 * One normalization rule for registration and login: trim + lower case (locale-independent).
 * "Alice@Example.com " and "alice@example.com" are the same account. The database enforces it too
 * ({@code ck_app_user_email_normalized}).
 */
public final class EmailAddress {

    private EmailAddress() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
