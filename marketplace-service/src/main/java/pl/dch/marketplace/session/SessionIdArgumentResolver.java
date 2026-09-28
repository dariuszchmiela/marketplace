package pl.dch.marketplace.session;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;

/**
 * Resolves a {@link SessionId} controller parameter from the {@code X-Session-Id} header.
 * This is the only place that knows how the anonymous session is transported.
 */
public class SessionIdArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER_NAME = "X-Session-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return SessionId.class.equals(parameter.getParameterType());
    }

    @Override
    public SessionId resolveArgument(MethodParameter parameter,
                                     ModelAndViewContainer mavContainer,
                                     NativeWebRequest webRequest,
                                     WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(HEADER_NAME);
        if (header == null || header.isBlank()) {
            throw new MarketplaceException(ErrorCode.MISSING_SESSION_ID,
                    "Missing required header " + HEADER_NAME);
        }
        try {
            return SessionId.of(header.trim());
        } catch (IllegalArgumentException ex) {
            throw new MarketplaceException(ErrorCode.INVALID_SESSION_ID,
                    "Header " + HEADER_NAME + " must be a UUID");
        }
    }
}
