package pl.dch.marketplace.session;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import pl.dch.marketplace.auth.AuthenticatedUser;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;

/**
 * Resolves a {@link SessionId} controller parameter from the <strong>authenticated principal</strong>
 * ({@code app_user.shopping_session_id}). The browser can no longer choose or send it: the Phase 1–4
 * {@code X-Session-Id} header is gone, and there is deliberately no fallback to any request header or parameter.
 * Controllers and services keep working with {@link SessionId} unchanged.
 */
public class SessionIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return SessionId.class.equals(parameter.getParameterType());
    }

    @Override
    public SessionId resolveArgument(MethodParameter parameter,
                                     ModelAndViewContainer mavContainer,
                                     NativeWebRequest webRequest,
                                     WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.sessionId();
        }
        // Unreachable when the security rules are right (these endpoints require authentication); fail closed anyway.
        throw new MarketplaceException(ErrorCode.AUTHENTICATION_REQUIRED, "Please log in");
    }
}
