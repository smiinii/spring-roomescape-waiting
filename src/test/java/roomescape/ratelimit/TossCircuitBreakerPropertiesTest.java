package roomescape.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TossCircuitBreakerPropertiesTest {

    @Test
    void fillsDefaultValuesTest() {
        TossCircuitBreakerProperties properties = new TossCircuitBreakerProperties(false, 0, 0, 0D,
                null, 0, 0);

        assertThat(properties.slidingWindowSize()).isEqualTo(20);
        assertThat(properties.minimumCalls()).isEqualTo(10);
        assertThat(properties.failureRateThreshold()).isEqualTo(50D);
        assertThat(properties.openDuration()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.halfOpenPermittedCalls()).isEqualTo(3);
        assertThat(properties.halfOpenSuccessThreshold()).isEqualTo(2);
    }

    @Test
    void rejectsMinimumCallsGreaterThanSlidingWindowSizeTest() {
        assertThatThrownBy(() -> new TossCircuitBreakerProperties(true, 5, 6, 50D,
                Duration.ofSeconds(10), 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidFailureRateThresholdTest() {
        assertThatThrownBy(() -> new TossCircuitBreakerProperties(true, 20, 10, 101D,
                Duration.ofSeconds(10), 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveOpenDurationTest() {
        assertThatThrownBy(() -> new TossCircuitBreakerProperties(true, 20, 10, 50D,
                Duration.ZERO, 3, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHalfOpenSuccessThresholdGreaterThanPermittedCallsTest() {
        assertThatThrownBy(() -> new TossCircuitBreakerProperties(true, 20, 10, 50D,
                Duration.ofSeconds(10), 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
