package roomescape.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

public class TossCircuitBreaker {

    private final TossCircuitBreakerProperties properties;
    private final LongSupplier nanoTime;
    private final Deque<Boolean> failures = new ArrayDeque<>();

    private State state = State.CLOSED;
    private long openedAtNanos;
    private int halfOpenAttempts;
    private int halfOpenInFlight;
    private int halfOpenSuccesses;

    public TossCircuitBreaker(TossCircuitBreakerProperties properties, LongSupplier nanoTime) {
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    public synchronized boolean tryAcquirePermission() {
        if (!properties.enabled()) {
            return true;
        }
        if (state == State.OPEN && canTryHalfOpen()) {
            state = State.HALF_OPEN;
            halfOpenAttempts = 0;
            halfOpenSuccesses = 0;
        }
        if (state == State.OPEN) {
            return false;
        }
        if (state == State.HALF_OPEN) {
            if (halfOpenAttempts >= properties.halfOpenPermittedCalls()) {
                return false;
            }
            halfOpenAttempts++;
            halfOpenInFlight++;
        }
        return true;
    }

    public synchronized void recordSuccess() {
        if (!properties.enabled()) {
            return;
        }
        if (state == State.HALF_OPEN) {
            completeHalfOpenAttempt();
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= properties.halfOpenSuccessThreshold() && halfOpenInFlight == 0) {
                close();
            }
            return;
        }
        if (state == State.CLOSED) {
            record(false);
            if (canOpen()) {
                open();
            }
        }
    }

    public synchronized void recordFailure() {
        if (!properties.enabled()) {
            return;
        }
        if (state == State.HALF_OPEN) {
            completeHalfOpenAttempt();
            open();
            return;
        }
        if (state == State.CLOSED) {
            record(true);
            if (canOpen()) {
                open();
            }
        }
    }

    public synchronized void recordIgnored() {
        if (!properties.enabled()) {
            return;
        }
        if (state == State.HALF_OPEN && halfOpenAttempts > 0) {
            halfOpenAttempts--;
            completeHalfOpenAttempt();
            if (halfOpenSuccesses >= properties.halfOpenSuccessThreshold() && halfOpenInFlight == 0) {
                close();
            }
        }
    }

    public synchronized State state() {
        return state;
    }

    private boolean canTryHalfOpen() {
        return nanoTime.getAsLong() - openedAtNanos >= properties.openDuration().toNanos();
    }

    private void record(boolean failed) {
        failures.addLast(failed);
        while (failures.size() > properties.slidingWindowSize()) {
            failures.removeFirst();
        }
    }

    private boolean canOpen() {
        if (failures.size() < properties.minimumCalls()) {
            return false;
        }
        long failureCount = failures.stream()
                .filter(Boolean::booleanValue)
                .count();
        double failureRate = failureCount * 100D / failures.size();
        return failureRate >= properties.failureRateThreshold();
    }

    private void open() {
        state = State.OPEN;
        openedAtNanos = nanoTime.getAsLong();
        halfOpenAttempts = 0;
        halfOpenInFlight = 0;
        halfOpenSuccesses = 0;
    }

    private void close() {
        state = State.CLOSED;
        failures.clear();
        halfOpenAttempts = 0;
        halfOpenInFlight = 0;
        halfOpenSuccesses = 0;
    }

    private void completeHalfOpenAttempt() {
        if (halfOpenInFlight > 0) {
            halfOpenInFlight--;
        }
    }

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
}
