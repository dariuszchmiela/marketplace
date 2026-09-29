package pl.dch.marketplace.payment;

import java.net.http.HttpClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the payment-service client from {@link PaymentClientProperties}.
 * <p>
 * Resilience4j is used through its core API (no annotations/AOP): the decoration order and the
 * failure classification are visible in plain code in {@link PaymentClient}, and the same factory
 * methods build the client in unit tests without Spring.
 */
@Configuration
@EnableConfigurationProperties(PaymentClientProperties.class)
class PaymentClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PaymentClientConfiguration.class);

    @Bean
    CircuitBreaker paymentCircuitBreaker(PaymentClientProperties properties) {
        return circuitBreaker(properties.circuitBreaker());
    }

    @Bean
    PaymentClient paymentClient(PaymentClientProperties properties, CircuitBreaker paymentCircuitBreaker) {
        return new PaymentClient(restClient(properties), paymentCircuitBreaker, retry(properties.retry()),
                properties.forwardScenarioHeader());
    }

    /**
     * JDK {@link HttpClient} with an explicit connect timeout; the read timeout is applied per request
     * by {@link JdkClientHttpRequestFactory} (time until the response headers arrive).
     */
    static RestClient restClient(PaymentClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    static CircuitBreaker circuitBreaker(PaymentClientProperties.CircuitBreaker properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                // After the wait, the next call moves the circuit to HALF_OPEN and is let through.
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .recordException(PaymentClient::isRecordedAsFailure)
                .build();
        CircuitBreaker circuitBreaker = CircuitBreaker.of("payment-service", config);
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("payment.circuit_breaker transition={}", event.getStateTransition()));
        return circuitBreaker;
    }

    static Retry retry(PaymentClientProperties.Retry properties) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(properties.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        properties.initialBackoff(), properties.backoffMultiplier(), properties.randomizationFactor()))
                .retryOnException(PaymentClient::isRetryable)
                .build();
        Retry retry = Retry.of("payment-service", config);
        retry.getEventPublisher().onRetry(event ->
                log.warn("payment.retry attempt={} waitMs={} failure=\"{}\"", event.getNumberOfRetryAttempts(),
                        event.getWaitInterval().toMillis(), PaymentClient.describe(event.getLastThrowable())));
        return retry;
    }
}
