package roomescape.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "toss-circuit-breaker")
public record TossCircuitBreakerProperties(
        boolean enabled,
        int slidingWindowSize,
        int minimumCalls,
        double failureRateThreshold,
        Duration openDuration,
        int halfOpenPermittedCalls,
        int halfOpenSuccessThreshold
) {

    public TossCircuitBreakerProperties {
        if (slidingWindowSize == 0) {
            slidingWindowSize = 20;
        }
        if (minimumCalls == 0) {
            minimumCalls = 10;
        }
        if (failureRateThreshold == 0D) {
            failureRateThreshold = 50D;
        }
        if (openDuration == null) {
            openDuration = Duration.ofSeconds(10);
        }
        if (halfOpenPermittedCalls == 0) {
            halfOpenPermittedCalls = 3;
        }
        if (halfOpenSuccessThreshold == 0) {
            halfOpenSuccessThreshold = 2;
        }
        validate(slidingWindowSize, minimumCalls, failureRateThreshold, openDuration,
                halfOpenPermittedCalls, halfOpenSuccessThreshold);
    }

    private static void validate(int slidingWindowSize, int minimumCalls, double failureRateThreshold,
                                 Duration openDuration, int halfOpenPermittedCalls,
                                 int halfOpenSuccessThreshold) {
        if (slidingWindowSize < 1) {
            throw new IllegalArgumentException("Circuit breaker sliding window size must be positive");
        }
        if (minimumCalls < 1 || minimumCalls > slidingWindowSize) {
            throw new IllegalArgumentException("Circuit breaker minimum calls must be between 1 and sliding window size");
        }
        if (failureRateThreshold <= 0D || failureRateThreshold > 100D) {
            throw new IllegalArgumentException("Circuit breaker failure rate threshold must be between 0 and 100");
        }
        if (openDuration.isZero() || openDuration.isNegative()) {
            throw new IllegalArgumentException("Circuit breaker open duration must be positive");
        }
        if (halfOpenPermittedCalls < 1) {
            throw new IllegalArgumentException("Circuit breaker half-open permitted calls must be positive");
        }
        if (halfOpenSuccessThreshold < 1 || halfOpenSuccessThreshold > halfOpenPermittedCalls) {
            throw new IllegalArgumentException(
                    "Circuit breaker half-open success threshold must be between 1 and permitted calls");
        }
    }
}
