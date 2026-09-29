package pl.dch.marketplace.auth;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import pl.dch.marketplace.IntegrationTestBase;
import pl.dch.marketplace.TestBrowser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Registration and login through the real endpoints, CSRF and session cookie included. */
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registrationNormalizesTheEmailStoresOnlyAHashAndLogsTheUserIn(CapturedOutput output) throws Exception {
        TestBrowser browser = anonymousBrowser();
        String email = "Alice." + UUID.randomUUID() + "@Example.COM";
        String password = "my secret password 1";

        MvcResult result = browser.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson("  " + email + " ", password)));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = result.getResponse().getContentAsString();
        String normalized = email.toLowerCase();
        assertThat(JsonPath.<String>read(body, "$.email")).isEqualTo(normalized);
        assertThat(body).doesNotContain("password", "hash", "shoppingSession");

        String storedHash = jdbcTemplate.queryForObject("select password_hash from app_user where email = ?", String.class, normalized);
        assertThat(storedHash).isNotEqualTo(password).startsWith("$2a$10$");
        assertThat(passwordEncoder.matches(password, storedHash)).isTrue();
        assertThat(passwordEncoder.matches("wrong password!!", storedHash)).isFalse();

        // Registration logged the user in: the session cookie works for /me.
        assertThat(browser.cookie(TestBrowser.SESSION_COOKIE)).isNotBlank();
        MvcResult me = browser.perform(get("/api/auth/me"));
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(me.getResponse().getContentAsString(), "$.email")).isEqualTo(normalized);
        assertThat(me.getResponse().getContentAsString()).doesNotContain("shoppingSession", "password");

        assertThat(output).doesNotContain(password).doesNotContain(storedHash).contains("auth.registered userId=");
    }

    @Test
    void sessionCookieIsHttpOnlyLaxAndSecureByDefault() throws Exception {
        TestBrowser browser = anonymousBrowser();

        MvcResult result = browser.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(uniqueEmail(), TEST_PASSWORD)));

        String setCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(TestBrowser.SESSION_COOKIE + "=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly", "SameSite=Lax", "Path=/", "Secure");
        // The CSRF cookie is the one cookie JavaScript must be able to read.
        browser.refreshCsrfToken();
        assertThat(browser.cookie(TestBrowser.CSRF_COOKIE)).isNotBlank();
    }

    @Test
    void duplicateEmailIsRejectedEvenWithDifferentCaseAndSpaces() throws Exception {
        String email = uniqueEmail();
        assertThat(register(anonymousBrowser(), email).getResponse().getStatus()).isEqualTo(201);

        MvcResult duplicate = register(anonymousBrowser(), "  " + email.toUpperCase() + " ");

        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(duplicate.getResponse().getContentAsString(), "$.code")).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void loginWithCorrectPasswordAndAnyEmailCase() throws Exception {
        TestBrowser browser = anonymousBrowser();

        MvcResult login = browser.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(user.email().toUpperCase(), TEST_PASSWORD)));

        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Number>read(login.getResponse().getContentAsString(), "$.id").longValue()).isEqualTo(user.userId());
        assertThat(browser.perform(get("/api/auth/me")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unknownEmailAndWrongPasswordLookExactlyTheSame(CapturedOutput output) throws Exception {
        MvcResult wrongPassword = login(anonymousBrowser(), user.email(), "a wrong password");
        MvcResult unknownEmail = login(anonymousBrowser(), uniqueEmail(), "a wrong password");

        for (MvcResult result : List.of(wrongPassword, unknownEmail)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code")).isEqualTo("INVALID_CREDENTIALS");
            assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(header -> header.startsWith(TestBrowser.SESSION_COOKIE + "=x"));
        }
        assertThat(JsonPath.<String>read(wrongPassword.getResponse().getContentAsString(), "$.message"))
                .isEqualTo(JsonPath.<String>read(unknownEmail.getResponse().getContentAsString(), "$.message"));
        assertThat(output).contains("auth.login_failed").doesNotContain("a wrong password").doesNotContain(user.email());
    }

    @Test
    void registrationValidatesEmailAndPasswordSize() throws Exception {
        assertThat(code(register(anonymousBrowser(), "not-an-email", TEST_PASSWORD))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(register(anonymousBrowser(), uniqueEmail(), "short"))).isEqualTo("VALIDATION_FAILED");
        // 40 characters but 80 bytes: BCrypt would silently ignore everything after byte 72.
        assertThat(code(register(anonymousBrowser(), uniqueEmail(), "ż".repeat(40)))).isEqualTo("INVALID_PASSWORD");
    }

    private TestBrowser anonymousBrowser() throws Exception {
        TestBrowser browser = new TestBrowser(mockMvc);
        browser.refreshCsrfToken();
        return browser;
    }

    private MvcResult register(TestBrowser browser, String email) throws Exception {
        return register(browser, email, TEST_PASSWORD);
    }

    private MvcResult register(TestBrowser browser, String email, String password) throws Exception {
        return browser.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(email, password)));
    }

    private MvcResult login(TestBrowser browser, String email, String password) throws Exception {
        return browser.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentialsJson(email, password)));
    }

    private static String code(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    private static String uniqueEmail() {
        return "someone-" + UUID.randomUUID() + "@example.com";
    }
}
