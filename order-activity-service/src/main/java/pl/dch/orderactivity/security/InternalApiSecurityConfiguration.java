package pl.dch.orderactivity.security;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class InternalApiSecurityConfiguration {

    @Bean
    FilterRegistrationBean<InternalApiTokenFilter> internalApiTokenFilter(
            @Value("${order-activity.security.api-token}") String token, JsonMapper jsonMapper) {
        if (token.startsWith("dev-only-")) {
            LoggerFactory.getLogger(InternalApiSecurityConfiguration.class).warn(
                    "security.dev_token_in_use: order-activity.security.api-token is the local development default; set ORDER_ACTIVITY_API_TOKEN");
        }
        FilterRegistrationBean<InternalApiTokenFilter> registration =
                new FilterRegistrationBean<>(new InternalApiTokenFilter(token, jsonMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        return registration;
    }
}
