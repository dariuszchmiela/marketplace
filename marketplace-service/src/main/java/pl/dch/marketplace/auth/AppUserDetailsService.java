package pl.dch.marketplace.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Used only by {@code DaoAuthenticationProvider} during login. It hides "user not found" behind the same
 * {@code BadCredentialsException} as a wrong password and still runs a BCrypt comparison against a dummy hash, so
 * neither the response nor its timing tells whether the email exists.
 */
@Service
class AppUserDetailsService implements UserDetailsService {

    static final List<GrantedAuthority> USER_AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final AppUserRepository users;

    AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(EmailAddress.normalize(email))
                .map(AppUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
    }

    /** Short-lived, only during authentication; the session stores {@link AuthenticatedUser} instead (no hash). */
    static final class AppUserDetails implements UserDetails {

        private final AuthenticatedUser user;
        private final String passwordHash;

        AppUserDetails(AppUser appUser) {
            this.user = appUser.toAuthenticatedUser();
            this.passwordHash = appUser.getPasswordHash();
        }

        AuthenticatedUser authenticatedUser() {
            return user;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return USER_AUTHORITIES;
        }

        @Override
        public String getPassword() {
            return passwordHash;
        }

        @Override
        public String getUsername() {
            return user.email();
        }
    }
}
