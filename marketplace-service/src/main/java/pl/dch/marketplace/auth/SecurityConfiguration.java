package pl.dch.marketplace.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * One authentication model: Spring Security + a server-side session (stored by Spring Session JDBC in PostgreSQL)
 * identified by the HttpOnly {@code MARKETPLACE_SESSION} cookie. Because the browser sends that cookie automatically,
 * CSRF protection is on for every state-changing request, including login and register.
 */
@Configuration
@EnableConfigurationProperties(AppSecurityProperties.class)
class SecurityConfiguration {

    static final String CSRF_COOKIE = "XSRF-TOKEN";
    static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
                                    SecurityContextRepository securityContextRepository,
                                    SecurityErrorHandler errorHandler) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // Training project: the API documentation stays public (it contains no data).
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                // SPA mode: token in a JS-readable XSRF-TOKEN cookie, sent back in the X-XSRF-TOKEN header.
                // Set the repository after spa(), which would otherwise install its own instance.
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .cors(Customizer.withDefaults())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(sessions -> sessions
                        .sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::changeSessionId))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                // JSON login/logout live in AuthController; no HTML login page, no Basic auth prompt.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // Defaults kept on purpose: X-Content-Type-Options nosniff, X-Frame-Options DENY,
                // Cache-Control no-store for responses, HSTS on HTTPS requests.
                .headers(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // BCrypt, cost 10: salted, deliberately slow one-way hash.
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE);
        repository.setHeaderName(CSRF_HEADER);
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax"));
        return repository;
    }

    /**
     * The session cookie, set explicitly (Spring Session's default would be a cookie named SESSION):
     * HttpOnly (not readable by JavaScript, so XSS cannot steal it), SameSite=Lax (not sent on cross-site
     * POST/fetch), Secure configurable (true by default; plain-HTTP local development sets it to false).
     */
    @Bean
    CookieSerializer sessionCookieSerializer(AppSecurityProperties properties) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(properties.sessionCookie().name());
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(properties.sessionCookie().secure());
        serializer.setSameSite(properties.sessionCookie().sameSite());
        serializer.setCookiePath("/");
        return serializer;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Run after a successful login/registration: a new session id (session fixation protection — an id the
     * attacker may have planted or seen before login becomes worthless) and a new CSRF token.
     */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    /**
     * CORS for a frontend on another origin (the Vite dev proxy makes development same-origin anyway).
     * Credentials are allowed, so only the configured origins are echoed back — never '*'.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AppSecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", CSRF_HEADER, "Idempotency-Key", "X-Payment-Scenario"));
        configuration.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
