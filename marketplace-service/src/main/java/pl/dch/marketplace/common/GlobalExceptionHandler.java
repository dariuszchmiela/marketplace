package pl.dch.marketplace.common;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates every exception into an {@link ApiError}.
 * <p>
 * Extends {@link ResponseEntityExceptionHandler} so that standard Spring MVC failures
 * (unreadable body, unsupported method, unknown path, ...) keep their correct HTTP status
 * but use our error body instead of the default one.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MarketplaceException.class)
    ResponseEntity<ApiError> handleMarketplaceException(MarketplaceException ex, WebRequest request) {
        return build(statusFor(ex.getCode()), ex.getCode(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, WebRequest request) {
        String message = "Invalid value for parameter '%s'".formatted(ex.getName());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, message, request, List.of());
    }

    /**
     * Safety net for expected races that no service translated into a more specific code:
     * optimistic lock conflicts, lock timeouts and deadlock victims ({@link ConcurrencyFailureException}
     * is the common parent). They are a 409 "try again", not a server error. No JPA/SQL details are exposed.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ApiError> handleConcurrencyFailure(ConcurrencyFailureException ex, WebRequest request) {
        log.warn("request.concurrency_conflict type={} path={}", ex.getClass().getSimpleName(), pathOf(request));
        return build(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION,
                "The data was changed by another request at the same time. Please refresh and try again.",
                request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Unexpected server error", request, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(ApiError.FieldError::field))
                .toList();
        return asObject(build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed", request, fieldErrors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> toFieldErrors(result).stream())
                .toList();
        return asObject(build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed", request, fieldErrors));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        return asObject(build(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "Malformed request body", request, List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
                                                                    HttpHeaders headers,
                                                                    HttpStatusCode status,
                                                                    WebRequest request) {
        return asObject(build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                "Resource not found", request, List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                         HttpHeaders headers,
                                                                         HttpStatusCode status,
                                                                         WebRequest request) {
        return asObject(build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED,
                ex.getMessage(), request, List.of()));
    }

    /**
     * Fallback for all other standard Spring MVC exceptions.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        HttpStatus resolved = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorCode code = resolved.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.REQUEST_FAILED;
        return asObject(build(resolved, code, resolved.getReasonPhrase(), request, List.of()));
    }

    static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED, MALFORMED_REQUEST, INVALID_PASSWORD, MISSING_IDEMPOTENCY_KEY,
                 INVALID_IDEMPOTENCY_KEY, INVALID_QUANTITY, REQUEST_FAILED -> HttpStatus.BAD_REQUEST;
            case PRODUCT_NOT_FOUND, CART_ITEM_NOT_FOUND, ORDER_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AUTHENTICATION_REQUIRED, INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            case ACCESS_DENIED, CSRF_FAILED -> HttpStatus.FORBIDDEN;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case PRODUCT_UNAVAILABLE, INSUFFICIENT_STOCK, CONCURRENT_STOCK_CHANGE, CONCURRENT_MODIFICATION, EMAIL_ALREADY_REGISTERED,
                 PAYMENT_RECONCILIATION_CONFLICT -> HttpStatus.CONFLICT;
            case PAYMENT_SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CART_EMPTY -> HttpStatus.UNPROCESSABLE_CONTENT;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static List<ApiError.FieldError> toFieldErrors(ParameterValidationResult result) {
        String field = result.getMethodParameter().getParameterName();
        return result.getResolvableErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .map(message -> new ApiError.FieldError(field, message))
                .toList();
    }

    private static ResponseEntity<ApiError> build(HttpStatus status,
                                                  ErrorCode code,
                                                  String message,
                                                  WebRequest request,
                                                  List<ApiError.FieldError> fieldErrors) {
        ApiError body = new ApiError(Instant.now(), status.value(), code, message, pathOf(request), fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    private static String pathOf(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            return servletRequest.getRequest().getRequestURI();
        }
        return null;
    }

    private static ResponseEntity<Object> asObject(ResponseEntity<ApiError> response) {
        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }
}
