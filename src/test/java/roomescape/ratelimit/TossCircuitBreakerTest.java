package roomescape.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import roomescape.ratelimit.TossCircuitBreaker.State;

class TossCircuitBreakerTest {

    @Test
    void keepsClosedBeforeMinimumCallsTest() {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreaker circuitBreaker = circuitBreaker(now);

        for (int i = 0; i < 9; i++) {
            circuitBreaker.recordFailure();
        }

        assertThat(circuitBreaker.state()).isEqualTo(State.CLOSED);
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
    }

    @Test
    void opensWhenFailureRateReachesThresholdAfterMinimumCallsTest() {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreaker circuitBreaker = circuitBreaker(now);

        for (int i = 0; i < 5; i++) {
            circuitBreaker.recordFailure();
            circuitBreaker.recordSuccess();
        }

        assertThat(circuitBreaker.state()).isEqualTo(State.OPEN);
        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();
    }

    @Test
    void remainsClosedWhenOldFailuresSlideOutOfWindowTest() {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreakerProperties properties = new TossCircuitBreakerProperties(true, 20, 20, 50D,
                Duration.ofSeconds(10), 3, 2);
        TossCircuitBreaker circuitBreaker = new TossCircuitBreaker(properties, now::get);

        for (int i = 0; i < 9; i++) {
            circuitBreaker.recordFailure();
        }
        for (int i = 0; i < 11; i++) {
            circuitBreaker.recordSuccess();
        }

        assertThat(circuitBreaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void changesToHalfOpenAfterOpenDurationTest() {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreaker circuitBreaker = circuitBreaker(now);
        open(circuitBreaker);

        now.addAndGet(Duration.ofSeconds(10).toNanos());

        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        assertThat(circuitBreaker.state()).isEqualTo(State.HALF_OPEN);
    }

    @Test
    void closesAfterEnoughHalfOpenSuccessesTest() {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreaker circuitBreaker = circuitBreaker(now);
        open(circuitBreaker);
        now.addAndGet(Duration.ofSeconds(10).toNanos());

        circuitBreaker.tryAcquirePermission();
        circuitBreaker.recordSuccess();
        circuitBreaker.tryAcquirePermission();
        circuitBreaker.recordSuccess();

        assertThat(circuitBreaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void reopensOnHalfOpenFailureTest() {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreaker circuitBreaker = circuitBreaker(now);
        open(circuitBreaker);
        now.addAndGet(Duration.ofSeconds(10).toNanos());

        circuitBreaker.tryAcquirePermission();
        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.state()).isEqualTo(State.OPEN);
        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();
    }

    @Test
    void rejectsExtraHalfOpenCallsBeyondPermitsTest() {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreaker circuitBreaker = circuitBreaker(now);
        open(circuitBreaker);
        now.addAndGet(Duration.ofSeconds(10).toNanos());

        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();
    }

    @Test
    void lateHalfOpenFailureReopensAfterEarlierSuccessesTest() {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreaker circuitBreaker = circuitBreaker(now);
        open(circuitBreaker);
        now.addAndGet(Duration.ofSeconds(10).toNanos());

        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();

        assertThat(circuitBreaker.state()).isEqualTo(State.HALF_OPEN);

        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.state()).isEqualTo(State.OPEN);
    }

    private TossCircuitBreaker circuitBreaker(AtomicLong now) {
        return new TossCircuitBreaker(new TossCircuitBreakerProperties(true, 20, 10, 50D,
                Duration.ofSeconds(10), 3, 2), now::get);
    }

    private void open(TossCircuitBreaker circuitBreaker) {
        for (int i = 0; i < 5; i++) {
            circuitBreaker.recordFailure();
            circuitBreaker.recordSuccess();
        }
    }
}
