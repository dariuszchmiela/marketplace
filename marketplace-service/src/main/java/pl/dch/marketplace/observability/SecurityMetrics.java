package pl.dch.marketplace.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Security events as plain counters, so attacks and client problems become visible on a dashboard (a spike of login
 * failures, CSRF rejections after a frontend deploy, a scraper without a management token).
 * <p>
 * Deliberately <strong>no tags</strong> identifying the user: no email, user id, session id or IP address. Who did
 * what is answered by the (access-controlled) logs, not by metric labels that end up in every dashboard.
 */
@Component
public class SecurityMetrics {

    private final Counter loginSuccess;
    private final Counter loginFailure;
    private final Counter csrfRejected;
    private final Counter authenticationRequired;
    private final Counter managementRejected;

    public SecurityMetrics(MeterRegistry registry) {
        this.loginSuccess = Counter.builder("auth.login.success")
                .description("Successful logins").register(registry);
        this.loginFailure = Counter.builder("auth.login.failure")
                .description("Failed logins (unknown email and wrong password are not distinguished)").register(registry);
        this.csrfRejected = Counter.builder("security.csrf.rejected")
                .description("State-changing requests rejected for a missing/invalid CSRF token").register(registry);
        this.authenticationRequired = Counter.builder("security.authentication.required")
                .description("Requests to protected API endpoints without a valid session (401)").register(registry);
        this.managementRejected = Counter.builder("security.management.rejected")
                .description("Management endpoint requests without a valid management token").register(registry);
    }

    public void loginSucceeded() {
        loginSuccess.increment();
    }

    public void loginFailed() {
        loginFailure.increment();
    }

    public void csrfRejected() {
        csrfRejected.increment();
    }

    public void authenticationRequired() {
        authenticationRequired.increment();
    }

    public void managementRejected() {
        managementRejected.increment();
    }
}
