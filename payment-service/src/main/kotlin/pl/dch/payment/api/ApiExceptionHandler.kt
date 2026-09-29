package pl.dch.payment.api

import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import pl.dch.payment.payments.IdempotencyKeyConflictException
import pl.dch.payment.payments.PaymentNotFoundException
import pl.dch.payment.simulation.InvalidScenarioException
import pl.dch.payment.simulation.SimulatedServerErrorException

/** Same error shape as marketplace-service, so both services are consistent for clients. */
data class ApiError(
    val timestamp: Instant,
    val status: Int,
    val code: String,
    val message: String,
    val path: String?,
    val fieldErrors: List<FieldError> = emptyList(),
) {
    data class FieldError(val field: String, val message: String?)
}

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler
    fun handleConflict(ex: IdempotencyKeyConflictException, request: WebRequest) =
        error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", ex.message, request)

    @ExceptionHandler
    fun handleNotFound(ex: PaymentNotFoundException, request: WebRequest) =
        error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.message, request)

    @ExceptionHandler
    fun handleInvalidScenario(ex: InvalidScenarioException, request: WebRequest) =
        error(HttpStatus.BAD_REQUEST, "INVALID_SCENARIO", ex.message, request)

    /** 503 = "not processed, nothing recorded": callers may safely retry with the same idempotency key. */
    @ExceptionHandler
    fun handleSimulatedFailure(ex: SimulatedServerErrorException, request: WebRequest) =
        error(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_SERVICE_UNAVAILABLE", ex.message, request)

    @ExceptionHandler
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException, request: WebRequest) =
        error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Invalid value for parameter '${ex.name}'", request)

    @ExceptionHandler
    fun handleUnexpected(ex: Exception, request: WebRequest): ResponseEntity<ApiError> {
        log.error("Unexpected error", ex)
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val fieldErrors = ex.bindingResult.fieldErrors
            .map { ApiError.FieldError(it.field, it.defaultMessage) }
            .sortedBy { it.field }
        return asAny(error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, fieldErrors))
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        asAny(error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request body", request))

    /** Fallback for the remaining standard Spring MVC exceptions (404 path, 405 method, ...). */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val status = HttpStatus.resolve(statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        val code = if (status.is5xxServerError) "INTERNAL_ERROR" else "REQUEST_FAILED"
        return asAny(error(status, code, status.reasonPhrase, request))
    }

    private fun error(
        status: HttpStatus,
        code: String,
        message: String?,
        request: WebRequest,
        fieldErrors: List<ApiError.FieldError> = emptyList(),
    ): ResponseEntity<ApiError> {
        val path = (request as? ServletWebRequest)?.request?.requestURI
        val body = ApiError(Instant.now(), status.value(), code, message ?: status.reasonPhrase, path, fieldErrors)
        return ResponseEntity.status(status).body(body)
    }

    private fun asAny(response: ResponseEntity<ApiError>): ResponseEntity<Any> =
        ResponseEntity.status(response.statusCode).body(response.body)

    private companion object {
        private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
