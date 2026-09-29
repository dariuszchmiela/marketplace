package pl.dch.marketplace.auth;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.security.*}. Allowed origins must be explicit: the API uses cookies, so CORS runs with
 * credentials, and credentials must never be combined with a wildcard origin.
 */
@Validated
@ConfigurationProperties("app.security")
public record AppSecurityProperties(@NotNull Cors cors, @NotNull SessionCookie sessionCookie) {

    /**
     * The session cookie (Spring Session's {@code DefaultCookieSerializer}, configured explicitly in
     * {@link SecurityConfiguration#sessionCookieSerializer}). HttpOnly and path are not configurable on purpose.
     */
    public record SessionCookie(@NotNull String name, boolean secure, @NotNull String sameSite) {
    }

    public record Cors(@NotNull List<String> allowedOrigins) {

        public Cors {
            if (allowedOrigins != null && allowedOrigins.stream().anyMatch(origin -> origin.contains("*"))) {
                throw new IllegalArgumentException("app.security.cors.allowed-origins must list explicit origins, no '*'");
            }
        }
    }
}
