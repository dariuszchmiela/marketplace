package pl.dch.marketplace.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;

@Service
public class UserRegistrationService {

    /** BCrypt only looks at the first 72 bytes; longer passwords would be silently truncated, so they are rejected. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserRegistrationService(AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthenticatedUser register(String email, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new MarketplaceException(ErrorCode.INVALID_PASSWORD,
                    "Password must not be longer than " + MAX_PASSWORD_BYTES + " bytes");
        }
        String normalized = EmailAddress.normalize(email);
        if (users.existsByEmail(normalized)) {
            throw emailTaken();
        }
        try {
            AppUser user = users.saveAndFlush(new AppUser(normalized, passwordEncoder.encode(password),
                    Instant.now().truncatedTo(ChronoUnit.MICROS)));
            return user.toAuthenticatedUser();
        } catch (DataIntegrityViolationException ex) {
            // Two registrations of the same email at the same moment: the unique constraint decides.
            throw emailTaken();
        }
    }

    private static MarketplaceException emailTaken() {
        return new MarketplaceException(ErrorCode.EMAIL_ALREADY_REGISTERED, "This email address is already registered");
    }
}
