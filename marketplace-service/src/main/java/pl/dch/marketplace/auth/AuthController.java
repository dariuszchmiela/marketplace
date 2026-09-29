package pl.dch.marketplace.auth;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;
import pl.dch.marketplace.observability.SecurityMetrics;

/**
 * JSON authentication endpoints for the React app. All POSTs need a CSRF token (fetch it with
 * {@code GET /api/auth/csrf} first) — also login and register, against login CSRF (an attacker's page logging the
 * victim into the attacker's account).
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    record CredentialsRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password) {

        /** Normalized before Bean Validation runs, so " Alice@Example.com " is validated (and stored) as "alice@example.com". */
        CredentialsRequest {
            email = EmailAddress.normalize(email);
        }

        /** Never print the password, e.g. in a validation error or debugger. */
        @Override
        public String toString() {
            return "CredentialsRequest[email=" + email + "]";
        }
    }

    /** What the browser may know about the current user. No shopping session id, no hash. */
    record UserResponse(long id, String email) {

        static UserResponse from(AuthenticatedUser user) {
            return new UserResponse(user.userId(), user.email());
        }
    }

    private final UserRegistrationService registration;
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final LogoutHandler logoutHandler;
    private final SecurityMetrics securityMetrics;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    AuthController(UserRegistrationService registration,
                   AuthenticationManager authenticationManager,
                   SessionAuthenticationStrategy sessionAuthenticationStrategy,
                   SecurityContextRepository securityContextRepository,
                   CsrfTokenRepository csrfTokenRepository,
                   SecurityMetrics securityMetrics) {
        this.securityMetrics = securityMetrics;
        this.registration = registration;
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        // Invalidate the server-side session (deleted from SPRING_SESSION) and drop the CSRF token.
        this.logoutHandler = new CompositeLogoutHandler(new SecurityContextLogoutHandler(),
                new CsrfLogoutHandler(csrfTokenRepository));
    }

    /**
     * Makes sure the {@code XSRF-TOKEN} cookie exists (creating the token is lazy). The value itself is read by the
     * client from the cookie and sent back in the {@code X-XSRF-TOKEN} header.
     */
    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        token.getToken();   // forces the deferred token to be generated and written to the cookie
        return Map.of("headerName", token.getHeaderName());
    }

    @PostMapping("/register")
    ResponseEntity<UserResponse> register(@Valid @RequestBody CredentialsRequest body,
                                          HttpServletRequest request, HttpServletResponse response) {
        AuthenticatedUser user = registration.register(body.email(), body.password());
        establishSession(user, request, response);
        log.info("auth.registered userId={}", user.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    /** Unknown email and wrong password are the same 401 INVALID_CREDENTIALS (and take the same BCrypt time). */
    @PostMapping("/login")
    UserResponse login(@Valid @RequestBody CredentialsRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (body.password().getBytes(StandardCharsets.UTF_8).length > UserRegistrationService.MAX_PASSWORD_BYTES) {
            securityMetrics.loginFailed();
            throw invalidCredentials();   // can never match a stored password
        }
        Authentication result;
        try {
            result = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(EmailAddress.normalize(body.email()), body.password()));
        } catch (AuthenticationException ex) {
            log.info("auth.login_failed reason={}", ex.getClass().getSimpleName());
            securityMetrics.loginFailed();
            throw invalidCredentials();
        }
        AuthenticatedUser user = ((AppUserDetailsService.AppUserDetails) result.getPrincipal()).authenticatedUser();
        establishSession(user, request, response);
        log.info("auth.login_succeeded userId={}", user.userId());
        securityMetrics.loginSucceeded();
        return UserResponse.from(user);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser user,
                                HttpServletRequest request, HttpServletResponse response) {
        logoutHandler.logout(request, response, contextHolder.getContext().getAuthentication());
        log.info("auth.logout userId={}", user.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return UserResponse.from(user);
    }

    /**
     * Stores the authenticated principal in the server-side session. The session strategy first changes the session
     * id (fixation protection) and replaces the CSRF token; the client fetches a fresh one afterwards.
     */
    private void establishSession(AuthenticatedUser user, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, null, AppUserDetailsService.USER_AUTHORITIES);
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private static MarketplaceException invalidCredentials() {
        return new MarketplaceException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
    }
}
