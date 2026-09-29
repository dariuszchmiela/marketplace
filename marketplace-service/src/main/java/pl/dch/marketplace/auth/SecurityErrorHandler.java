package pl.dch.marketplace.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import pl.dch.marketplace.common.ApiError;
import pl.dch.marketplace.common.ErrorCode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes Spring Security speak the API's error format: JSON {@link ApiError}, never an HTML page or a redirect to a
 * login form. 401 = "who are you?" (no/expired session), 403 = "I know who you are, but no" (or missing/invalid
 * CSRF token, which gets its own code so the client can refresh the token).
 */
@Component
class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorHandler.class);

    private final JsonMapper jsonMapper;

    SecurityErrorHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        log.info("security.authentication_required method={} path={}", request.getMethod(), request.getRequestURI());
        write(response, request, HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED, "Please log in");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (ex instanceof CsrfException) {
            log.info("security.csrf_rejected userId={} method={} path={}", currentUserId(), request.getMethod(),
                    request.getRequestURI());
            write(response, request, HttpStatus.FORBIDDEN, ErrorCode.CSRF_FAILED,
                    "Missing or invalid CSRF token; fetch /api/auth/csrf and retry");
            return;
        }
        log.info("security.access_denied userId={} method={} path={}", currentUserId(), request.getMethod(),
                request.getRequestURI());
        write(response, request, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "Access denied");
    }

    private void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, ErrorCode code,
                       String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(),
                new ApiError(Instant.now(), status.value(), code, message, request.getRequestURI(), List.of()));
    }

    private static String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                ? user.getName() : "anonymous";
    }
}
