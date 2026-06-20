package roomescape.payment;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import roomescape.domain.exception.DomainErrorCode;
import roomescape.domain.exception.RoomEscapeException;
import roomescape.ratelimit.BackoffSleeper;
import roomescape.ratelimit.RetryDeadline;

@Service
public class PaymentService {

    private static final int MAX_CONFIRM_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(200);

    private final PaymentGateway paymentGateway;
    private final BackoffSleeper sleeper;
    private final RetryDeadline retryDeadline;
    private final Duration attemptTimeout;
    private final Duration confirmDeadline;

    public PaymentService(
            PaymentGateway paymentGateway,
            BackoffSleeper sleeper,
            RetryDeadline retryDeadline,
            @Value("${toss.read-timeout}") Duration attemptTimeout,
            @Value("${toss.confirm-deadline}") Duration confirmDeadline
    ) {
        validatePositive(attemptTimeout, "attemptTimeout");
        validatePositive(confirmDeadline, "confirmDeadline");
        this.paymentGateway = paymentGateway;
        this.sleeper = sleeper;
        this.retryDeadline = retryDeadline;
        this.attemptTimeout = attemptTimeout;
        this.confirmDeadline = confirmDeadline;
    }

    public PaymentConfirmationResult confirm(String paymentKey, String orderId, String idempotencyKey, Long amount) {
        return retryDeadline.callWithin(confirmDeadline, () -> confirmWithDeadline(paymentKey, orderId, idempotencyKey,
                amount));
    }

    private PaymentConfirmationResult confirmWithDeadline(String paymentKey, String orderId, String idempotencyKey,
                                                          Long amount) {
        PaymentConfirmation confirmation = new PaymentConfirmation(paymentKey, orderId, idempotencyKey, amount);
        RoomEscapeException lastRetryableException = null;

        for (int attempt = 1; attempt <= MAX_CONFIRM_ATTEMPTS; attempt++) {
            try {
                PaymentResult result = paymentGateway.confirm(confirmation);
                return confirmationResult(result, orderId, amount);
            } catch (RoomEscapeException exception) {
                if (!isRetryable(exception)) {
                    return PaymentConfirmationResult.failure(exception.code());
                }
                lastRetryableException = exception;
                if (attempt < MAX_CONFIRM_ATTEMPTS) {
                    Duration backoff = retryBackoff(attempt);
                    if (!canRetry(backoff)) {
                        break;
                    }
                    sleeper.sleep(backoff);
                }
            }
        }
        if (lastRetryableException.code() == DomainErrorCode.PAYMENT_UNKNOWN) {
            return PaymentConfirmationResult.unknownResult();
        }
        return PaymentConfirmationResult.failure(lastRetryableException.code());
    }

    private PaymentConfirmationResult confirmationResult(PaymentResult result, String orderId, Long amount) {
        if (!PaymentStatus.DONE.name().equals(result.status()) || !result.orderId().equals(orderId)) {
            return PaymentConfirmationResult.failure(DomainErrorCode.PAYMENT_FAILED);
        }
        if (!result.approvedAmount().equals(amount)) {
            return PaymentConfirmationResult.failure(DomainErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        return PaymentConfirmationResult.success(result);
    }

    private boolean isRetryable(RoomEscapeException exception) {
        return exception.code() == DomainErrorCode.PAYMENT_RETRYABLE
                || exception.code() == DomainErrorCode.PAYMENT_UNKNOWN;
    }

    private Duration retryBackoff(int attempt) {
        return RETRY_BACKOFF.multipliedBy(attempt);
    }

    private boolean canRetry(Duration backoff) {
        return retryDeadline.canFit(backoff.plus(attemptTimeout));
    }

    private void validatePositive(Duration duration, String name) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
