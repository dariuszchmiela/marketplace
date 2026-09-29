package pl.dch.marketplace.session;

import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

/**
 * {@link SessionId} is resolved from the authenticated principal, not from the request, so it must not appear as a
 * parameter in the OpenAPI document. Authentication is the {@code MARKETPLACE_SESSION} cookie set by
 * {@code POST /api/auth/login}; state-changing calls additionally need the {@code X-XSRF-TOKEN} header.
 */
@Configuration
class SessionOpenApiConfig {

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(SessionId.class);
    }
}
