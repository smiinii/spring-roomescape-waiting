package roomescape.ratelimit;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class RetryDeadline {

    private final LongSupplier nanoTime;
    private final ThreadLocal<Long> deadlineNanos = new ThreadLocal<>();

    public RetryDeadline(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public <T> T callWithin(Duration deadline, Supplier<T> supplier) {
        Long previousDeadline = deadlineNanos.get();
        long currentDeadline = nanoTime.getAsLong() + deadline.toNanos();
        if (previousDeadline == null || currentDeadline < previousDeadline) {
            deadlineNanos.set(currentDeadline);
        }
        try {
            return supplier.get();
        } finally {
            if (previousDeadline == null) {
                deadlineNanos.remove();
            } else {
                deadlineNanos.set(previousDeadline);
            }
        }
    }

    public boolean canFit(Duration duration) {
        Long currentDeadline = deadlineNanos.get();
        if (currentDeadline == null) {
            return true;
        }
        return currentDeadline - nanoTime.getAsLong() > duration.toNanos();
    }
}
