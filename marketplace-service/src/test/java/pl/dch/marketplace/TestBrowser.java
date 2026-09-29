package pl.dch.marketplace;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * What a browser does for the React app, for MockMvc tests that go through the real security chain:
 * keeps the cookies it received ({@code MARKETPLACE_SESSION}, {@code XSRF-TOKEN}), sends them back, and copies
 * the CSRF cookie into the {@code X-XSRF-TOKEN} header on state-changing requests (like the frontend's API client).
 */
public final class TestBrowser {

    public static final String SESSION_COOKIE = "MARKETPLACE_SESSION";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final MockMvc mockMvc;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public TestBrowser(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** Sends the request with cookies (and the CSRF header for unsafe methods) and remembers new cookies. */
    public MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(prepare(request)).andReturn();
        remember(result);
        return result;
    }

    /** Fetches a CSRF token the way the frontend does: {@code GET /api/auth/csrf} sets the XSRF-TOKEN cookie. */
    public void refreshCsrfToken() throws Exception {
        perform(get("/api/auth/csrf"));
    }

    /** Adds this browser's cookies (and CSRF header for unsafe methods) to a request, without sending it. */
    public MockHttpServletRequestBuilder prepare(MockHttpServletRequestBuilder request) {
        MockHttpServletRequestBuilder prepared = request;
        if (!cookies.isEmpty()) {
            prepared = prepared.cookie(cookies.entrySet().stream()
                    .map(cookie -> new Cookie(cookie.getKey(), cookie.getValue()))
                    .toArray(Cookie[]::new));
        }
        String csrf = cookies.get(CSRF_COOKIE);
        return prepared.with(built -> {
            if (csrf != null && UNSAFE_METHODS.contains(built.getMethod().toUpperCase(Locale.ROOT))) {
                built.addHeader(CSRF_HEADER, csrf);
            }
            return built;
        });
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    public void forgetCookie(String name) {
        cookies.remove(name);
    }

    /** Parses {@code Set-Cookie} headers: new values are stored, {@code Max-Age=0} / empty values delete the cookie. */
    public void remember(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            String[] parts = header.split(";");
            String[] nameValue = parts[0].split("=", 2);
            String name = nameValue[0].trim();
            String value = nameValue.length > 1 ? nameValue[1].trim() : "";
            boolean expired = value.isEmpty();
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].trim().equalsIgnoreCase("Max-Age=0")) {
                    expired = true;
                }
            }
            if (expired) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0 || cookie.getValue() == null || cookie.getValue().isEmpty()) {
                cookies.remove(cookie.getName());
            } else {
                cookies.put(cookie.getName(), cookie.getValue());
            }
        }
    }
}
