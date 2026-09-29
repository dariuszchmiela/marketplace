package pl.dch.marketplace.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.TestBrowser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Server-side sessions in PostgreSQL: storage, logout, fixation protection, expiry. */
class SessionLifecycleIntegrationTest extends IntegrationTestBase {

    @Test
    void theSessionLivesInPostgresqlWithTheUserIdAsPrincipalAndTheConfiguredTimeout() {
        String sessionId = sessionIdOf(user.browser());

        var row = jdbcTemplate.queryForMap(
                "select principal_name, max_inactive_interval from spring_session where session_id = ?", sessionId);

        // Another marketplace instance reading this table would accept the same cookie.
        assertThat(row.get("principal_name")).isEqualTo(Long.toString(user.userId()));   // user id, not the email
        assertThat(row.get("max_inactive_interval")).isEqualTo(20 * 60);   // spring.session.timeout=20m in the tests
    }

    @Test
    void logoutInvalidatesTheServerSideSessionSoTheOldCookieIsWorthless() throws Exception {
        String oldCookie = user.browser().cookie(TestBrowser.SESSION_COOKIE);
        String sessionId = sessionIdOf(user.browser());

        MvcResult logout = user.browser().perform(post("/api/auth/logout"));

        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbcTemplate.queryForObject("select count(*) from spring_session where session_id = ?",
                Integer.class, sessionId)).isZero();
        // Replaying the stolen/old cookie after logout gets nothing.
        mockMvc.perform(get("/api/cart").cookie(new Cookie(TestBrowser.SESSION_COOKIE, oldCookie)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void logoutNeedsTheCsrfTokenToo() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie(TestBrowser.SESSION_COOKIE, user.browser().cookie(TestBrowser.SESSION_COOKIE))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_FAILED"));

        // Not logged out.
        mockMvc.perform(getWithSession("/api/auth/me")).andExpect(status().isOk());
    }

    @Test
    void loginReplacesTheSessionIdSessionFixationProtection() throws Exception {
        TestBrowser browser = user.browser();
        String sessionBeforeLogin = browser.cookie(TestBrowser.SESSION_COOKIE);

        // Log in again from the same browser (e.g. as another account): the id an attacker may know is replaced.
        MvcResult login = browser.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(user.email(), TEST_PASSWORD)));

        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        String sessionAfterLogin = browser.cookie(TestBrowser.SESSION_COOKIE);
        assertThat(sessionAfterLogin).isNotBlank().isNotEqualTo(sessionBeforeLogin);
        mockMvc.perform(get("/api/auth/me").cookie(new Cookie(TestBrowser.SESSION_COOKIE, sessionBeforeLogin)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").cookie(new Cookie(TestBrowser.SESSION_COOKIE, sessionAfterLogin)))
                .andExpect(status().isOk());
    }

    @Test
    void aSessionIdChosenByAnAttackerIsNeverAdopted() throws Exception {
        TestBrowser browser = new TestBrowser(mockMvc);
        String planted = Base64.getEncoder().encodeToString("11111111-2222-3333-4444-555555555555".getBytes(StandardCharsets.UTF_8));
        browser.setCookie(TestBrowser.SESSION_COOKIE, planted);
        browser.refreshCsrfToken();

        browser.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(user.email(), TEST_PASSWORD)));

        assertThat(browser.cookie(TestBrowser.SESSION_COOKIE)).isNotEqualTo(planted);
        mockMvc.perform(get("/api/auth/me").cookie(new Cookie(TestBrowser.SESSION_COOKIE, planted)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginClearsTheCsrfCookieAndTheClientGetsAFreshToken() throws Exception {
        TestBrowser browser = user.browser();
        String tokenBeforeLogin = browser.cookie(TestBrowser.CSRF_COOKIE);

        browser.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(user.email(), TEST_PASSWORD)));

        assertThat(browser.cookie(TestBrowser.CSRF_COOKIE)).isNull();   // cleared by CsrfAuthenticationStrategy
        browser.refreshCsrfToken();
        assertThat(browser.cookie(TestBrowser.CSRF_COOKIE)).isNotBlank().isNotEqualTo(tokenBeforeLogin);
        // Note: the cookie repository is the stateless double-submit pattern — the server only checks that the
        // header equals the cookie, it keeps no copy. Clearing the cookie is the whole "rotation"; protection rests
        // on other sites being unable to read or write our cookies (SameSite, same-origin cookie scope).
    }

    @Test
    void anExpiredSessionIsRejected() throws Exception {
        String sessionId = sessionIdOf(user.browser());
        mockMvc.perform(getWithSession("/api/auth/me")).andExpect(status().isOk());

        // Pretend the last request was longer ago than the 30 minute idle timeout.
        jdbcTemplate.update("""
                update spring_session
                set last_access_time = last_access_time - (max_inactive_interval + 60) * 1000,
                    expiry_time = expiry_time - (max_inactive_interval + 60) * 1000
                where session_id = ?""", sessionId);

        mockMvc.perform(getWithSession("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    /** Spring Session writes the session id Base64-encoded into the cookie. */
    private static String sessionIdOf(TestBrowser browser) {
        return new String(Base64.getDecoder().decode(browser.cookie(TestBrowser.SESSION_COOKIE)), StandardCharsets.UTF_8);
    }
}
