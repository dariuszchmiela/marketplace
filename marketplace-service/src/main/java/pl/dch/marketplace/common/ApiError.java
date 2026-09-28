package pl.dch.marketplace.common;

import java.time.Instant;
import java.util.List;

/**
 * The single error body shape returned by every endpoint.
 */
public record ApiError(
        Instant timestamp,
        int status,
        ErrorCode code,
        String message,
        String path,
        List<FieldError> fieldErrors
) {

    public record FieldError(String field, String message) {
    }
}
