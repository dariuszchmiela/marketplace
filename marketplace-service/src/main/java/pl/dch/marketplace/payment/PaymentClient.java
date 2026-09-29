package pl.dch.marketplace.payment;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import pl.dch.marketplace.common.ErrorCode;
import pl.dch.marketplace.common.MarketplaceException;

/**
 * The only class that talks HTTP to payment-service.
 * <p>
 * Every call goes through {@code Retry(CircuitBreaker(http call))}: each attempt is recorded by the
 * circuit breaker, and an open circuit is not retried. All attempts for one order use the same
 * payment idempotency key, so a retry can never create a second payment.
 * <p>
 * Failure classification (one attempt):
 * <table>
 *   <tr><th>failure</th><th>retried</th><th>proves "not processed"</th><th>counts for circuit breaker</th></tr>
 *   <tr><td>connection refused / connect timeout</td><td>yes</td><td>yes</td><td>yes</td></tr>
 *   <tr><td>503 (payment-service contract: nothing recorded)</td><td>yes</td><td>yes</td><td>yes</td></tr>
 *   <tr><td>502, 504 (gateway: may have been processed)</td><td>yes</td><td>no</td><td>yes</td></tr>
 *   <tr><td>500 and other 5xx</td><td>no</td><td>no</td><td>yes</td></tr>
 *   <tr><td>response (read) timeout, other I/O error</td><td>no</td><td>no</td><td>yes</td></tr>
 *   <tr><td>409 idempotency conflict</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>other 4xx (invalid request)</td><td>no</td><td>yes</td><td>no</td></tr>
 *   <tr><td>circuit open</td><td>no</td><td>yes</td><td>-</td></tr>
 * </table>
 * A read timeout is not retried: payment-service is already slow, a retry would multiply the load and
 * the shopper's waiting time, and the result can be recovered cheaply by reconciliation.
 */
public class PaymentClient {

    public static final String SCENARIO_HEADER = "X-Payment-Scenario";

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(502, 503, 504);

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final boolean forwardScenarioHeader;

    public PaymentClient(RestClient restClient, CircuitBreaker circuitBreaker, Retry retry, boolean forwardScenarioHeader) {
        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.forwardScenarioHeader = forwardScenarioHeader;
    }

    /**
     * Never throws for remote failures: every result is mapped to a {@link PaymentOutcome}.
     *
     * @param scenario optional failure scenario, forwarded only when enabled in configuration
     */
    public PaymentOutcome pay(RemotePayment.Request request, @Nullable String scenario) {
        requireNoTransaction();
        AtomicInteger attempts = new AtomicInteger();
        // Set when any attempt may have reached payment-service without a definitive answer.
        AtomicBoolean ambiguousAttempt = new AtomicBoolean();

        Supplier<RemotePayment.Response> attempt = () -> {
            int number = attempts.incrementAndGet();
            log.info("payment.request orderId={} idempotencyKey={} attempt={}",
                    request.orderId(), request.idempotencyKey(), number);
            try {
                return post(request, scenario);
            } catch (RuntimeException ex) {
                if (!provesNotProcessed(ex)) {
                    ambiguousAttempt.set(true);
                }
                log.warn("payment.attempt_failed orderId={} idempotencyKey={} attempt={} failure=\"{}\"",
                        request.orderId(), request.idempotencyKey(), number, describe(ex));
                throw ex;
            }
        };

        try {
            RemotePayment.Response response = decorate(attempt).get();
            log.info("payment.result orderId={} idempotencyKey={} paymentId={} status={} attempts={}",
                    request.orderId(), request.idempotencyKey(), response.paymentId(), response.status(), attempts.get());
            return switch (response.status()) {
                case SUCCEEDED -> new PaymentOutcome.Succeeded(response.paymentId());
                case DECLINED -> new PaymentOutcome.Declined(response.paymentId());
            };
        } catch (RuntimeException ex) {
            String reason = describe(ex);
            boolean unknown = ambiguousAttempt.get() || !provesNotProcessed(ex);
            log.warn("payment.no_result orderId={} idempotencyKey={} attempts={} outcome={} reason=\"{}\"",
                    request.orderId(), request.idempotencyKey(), attempts.get(),
                    unknown ? "UNKNOWN" : "NOT_PROCESSED", reason);
            return unknown ? new PaymentOutcome.Unknown(reason) : new PaymentOutcome.NotProcessed(reason);
        }
    }

    /**
     * Reconciliation lookup. Empty when payment-service has no payment for the key.
     *
     * @throws MarketplaceException {@link ErrorCode#PAYMENT_SERVICE_UNAVAILABLE} when payment-service cannot answer
     */
    public Optional<RemotePayment.Response> findByIdempotencyKey(UUID idempotencyKey) {
        requireNoTransaction();
        Supplier<RemotePayment.Response> lookup = () -> restClient.get()
                .uri("/api/payments/by-idempotency-key/{key}", idempotencyKey)
                .retrieve()
                .body(RemotePayment.Response.class);
        try {
            RemotePayment.Response response = decorate(lookup).get();
            log.info("payment.lookup idempotencyKey={} paymentId={} status={}",
                    idempotencyKey, response.paymentId(), response.status());
            return Optional.of(response);
        } catch (HttpClientErrorException.NotFound ex) {
            log.info("payment.lookup idempotencyKey={} result=NOT_FOUND", idempotencyKey);
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("payment.lookup_failed idempotencyKey={} failure=\"{}\"", idempotencyKey, describe(ex));
            throw new MarketplaceException(ErrorCode.PAYMENT_SERVICE_UNAVAILABLE,
                    "Payment service is not available, try again later");
        }
    }

    private RemotePayment.Response post(RemotePayment.Request request, @Nullable String scenario) {
        return restClient.post()
                .uri("/api/payments")
                .headers(headers -> {
                    if (forwardScenarioHeader && scenario != null) {
                        headers.set(SCENARIO_HEADER, scenario);
                    }
                })
                .body(request)
                .retrieve()
                .body(RemotePayment.Response.class);
    }

    private <T> Supplier<T> decorate(Supplier<T> call) {
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call));
    }

    /**
     * Guard for the most important rule of this flow: waiting for a remote service must never hold
     * a database transaction (and its connection) open.
     */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("payment-service must not be called inside a database transaction");
        }
    }

    static boolean isRetryable(Throwable failure) {
        return isConnectFailure(failure)
                || failure instanceof HttpServerErrorException serverError
                && RETRYABLE_STATUSES.contains(serverError.getStatusCode().value());
    }

    /** Only transport failures and 5xx say something about payment-service's health; 4xx do not. */
    static boolean isRecordedAsFailure(Throwable failure) {
        return failure instanceof HttpServerErrorException || failure instanceof ResourceAccessException;
    }

    static boolean provesNotProcessed(Throwable failure) {
        return isConnectFailure(failure)
                || failure instanceof CallNotPermittedException
                || failure instanceof HttpServerErrorException serverError
                && serverError.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()
                || failure instanceof HttpClientErrorException clientError
                && clientError.getStatusCode().value() != HttpStatus.CONFLICT.value();
    }

    /** The request never left this process: no connection could be established. */
    static boolean isConnectFailure(Throwable failure) {
        if (!(failure instanceof ResourceAccessException)) {
            return false;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof HttpConnectTimeoutException
                    || cause instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    static String describe(Throwable failure) {
        if (failure instanceof CallNotPermittedException) {
            return "circuit breaker open";
        }
        if (failure instanceof HttpServerErrorException || failure instanceof HttpClientErrorException) {
            return "HTTP " + ((HttpStatusCodeException) failure).getStatusCode().value();
        }
        if (isConnectFailure(failure)) {
            return "connection failed";
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) {
                return "response timeout";
            }
        }
        return failure.getClass().getSimpleName();
    }
}
