package roomescape.ratelimit;

public class TossCircuitBreakerOpenException extends RuntimeException {

    public TossCircuitBreakerOpenException(String message) {
        super(message);
    }
}
