package pl.dch.marketplace.payment;

import java.net.http.HttpClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
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
    private static final String NAME = "payment-service";

    @Bean
    CircuitBreakerRegistry paymentCircuitBreakerRegistry(PaymentClientProperties properties) {
        return CircuitBreakerRegistry.of(circuitBreakerConfig(properties.circuitBreaker()));
    }

    @Bean
    CircuitBreaker paymentCircuitBreaker(CircuitBreakerRegistry paymentCircuitBreakerRegistry) {
        return withTransitionLog(paymentCircuitBreakerRegistry.circuitBreaker(NAME));
    }

    @Bean
    RetryRegistry paymentRetryRegistry(PaymentClientProperties properties) {
        return RetryRegistry.of(retryConfig(properties.retry()));
    }

    @Bean
    Retry paymentRetry(RetryRegistry paymentRetryRegistry) {
        return withRetryLog(paymentRetryRegistry.retry(NAME));
    }

    @Bean
    PaymentClient paymentClient(PaymentClientProperties properties, CircuitBreaker paymentCircuitBreaker, Retry paymentRetry,
                                ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        return new PaymentClient(restClient(properties, observationRegistry), paymentCircuitBreaker, paymentRetry,
                properties.forwardScenarioHeader(), new PaymentClientMetrics(meterRegistry));
    }

    /**
     * Resilience4j's own Micrometer binding: circuit breaker state (0/1 per state), buffered/failed/not-permitted
     * calls, failure rate; retry outcomes (successful with/without retry, failed with/without retry).
     */
    @Bean
    MeterBinder paymentResilienceMetrics(CircuitBreakerRegistry paymentCircuitBreakerRegistry,
                                         RetryRegistry paymentRetryRegistry) {
        return registry -> {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(paymentCircuitBreakerRegistry).bindTo(registry);
            TaggedRetryMetrics.ofRetryRegistry(paymentRetryRegistry).bindTo(registry);
        };
    }

    /**
     * JDK {@link HttpClient} with an explicit connect timeout; the read timeout is applied per request
     * by {@link JdkClientHttpRequestFactory} (time until the response headers arrive).
     * <p>
     * The {@link ObservationRegistry} makes every HTTP attempt an {@code http.client.requests} observation: a timer
     * (tagged with the uri <em>template</em>, never the concrete key) and a client span whose W3C {@code traceparent}
     * header is injected into the request, so payment-service continues the checkout's trace.
     */
    static RestClient restClient(PaymentClientProperties properties, ObservationRegistry observationRegistry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                // Service-to-service authentication: every payment and reconciliation call carries the shared token.
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
                .build();
    }

    /** Unit tests: a standalone circuit breaker with the same configuration and logging. */
    static CircuitBreaker circuitBreaker(PaymentClientProperties.CircuitBreaker properties) {
        return withTransitionLog(CircuitBreaker.of(NAME, circuitBreakerConfig(properties)));
    }

    static Retry retry(PaymentClientProperties.Retry properties) {
        return withRetryLog(Retry.of(NAME, retryConfig(properties)));
    }

    private static CircuitBreakerConfig circuitBreakerConfig(PaymentClientProperties.CircuitBreaker properties) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                // After the wait, the next call moves the circuit to HALF_OPEN and is let through.
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .recordException(PaymentClient::isRecordedAsFailure)
                .build();
    }

    private static CircuitBreaker withTransitionLog(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("payment.circuit_breaker transition={}", event.getStateTransition()));
        return circuitBreaker;
    }

    private static RetryConfig retryConfig(PaymentClientProperties.Retry properties) {
        return RetryConfig.custom()
                .maxAttempts(properties.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        properties.initialBackoff(), properties.backoffMultiplier(), properties.randomizationFactor()))
                .retryOnException(PaymentClient::isRetryable)
                .build();
    }

    private static Retry withRetryLog(Retry retry) {
        retry.getEventPublisher().onRetry(event ->
                log.warn("payment.retry attempt={} waitMs={} failure=\"{}\"", event.getNumberOfRetryAttempts(),
                        event.getWaitInterval().toMillis(), PaymentClient.describe(event.getLastThrowable())));
        return retry;
    }
}
