package pl.dch.marketplace.common;

/**
 * Expected business failure (missing resource, broken business rule).
 * Mapped to an HTTP response by {@link GlobalExceptionHandler}, so domain and service code
 * stays free of HTTP concepts.
 */
public class MarketplaceException extends RuntimeException {

    private final ErrorCode code;

    public MarketplaceException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
