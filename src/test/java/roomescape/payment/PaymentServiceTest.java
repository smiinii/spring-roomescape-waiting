package roomescape.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import roomescape.domain.exception.DomainErrorCode;
import roomescape.domain.exception.RoomEscapeException;
import roomescape.ratelimit.RetryDeadline;

class PaymentServiceTest {

    private PaymentGateway paymentGateway;
    private PaymentService paymentService;
    private RecordingSleeper sleeper;
    private FakeNanoTime nanoTime;

    @BeforeEach
    void beforeEach() {
        paymentGateway = Mockito.mock(PaymentGateway.class);
        sleeper = new RecordingSleeper();
        nanoTime = new FakeNanoTime();
        paymentService = new PaymentService(paymentGateway, sleeper, new RetryDeadline(nanoTime), Duration.ofSeconds(10),
                Duration.ofMinutes(1));
    }

    @Test
    void confirmDelegatesGatewayTest() {
        PaymentResult expected = new PaymentResult("payment_key", "order_test", "DONE", 50000L);

        when(paymentGateway.confirm(new PaymentConfirmation("payment_key", "order_test", "order_test", 50000L)))
                .thenReturn(expected);

        PaymentConfirmationResult result = paymentService.confirm("payment_key", "order_test", "order_test", 50000L);

        assertThat(result.paymentResult()).isEqualTo(expected);
        assertThat(result.unknown()).isFalse();
        assertThat(result.failed()).isFalse();
        verify(paymentGateway, times(1)).confirm(new PaymentConfirmation("payment_key", "order_test", "order_test", 50000L));
        assertThat(sleeper.durations()).isEmpty();
    }

    @Test
    void confirmRetriesRetryableGatewayErrorWithSameIdempotencyKeyTest() {
        PaymentResult expected = new PaymentResult("payment_key", "order_test", "DONE", 50000L);
        PaymentConfirmation confirmation = new PaymentConfirmation("payment_key", "order_test", "stored_key", 50000L);
        when(paymentGateway.confirm(confirmation))
                .thenThrow(new RoomEscapeException(DomainErrorCode.PAYMENT_RETRYABLE))
                .thenThrow(new RoomEscapeException(DomainErrorCode.PAYMENT_RETRYABLE))
                .thenReturn(expected);

        PaymentConfirmationResult result = paymentService.confirm("payment_key", "order_test", "stored_key", 50000L);

        assertThat(result.paymentResult()).isEqualTo(expected);
        assertThat(result.unknown()).isFalse();
        assertThat(result.failed()).isFalse();
        assertConfirmAttemptsUseSameIdempotencyKey("stored_key", 3);
        assertThat(sleeper.durations()).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
    }

    @Test
    void confirmRetriesUnknownPaymentAndKeepsUnknownAfterRetryBudgetTest() {
        PaymentConfirmation confirmation = new PaymentConfirmation("payment_key", "order_test", "stored_key", 50000L);
        when(paymentGateway.confirm(confirmation))
                .thenThrow(new RoomEscapeException(DomainErrorCode.PAYMENT_UNKNOWN));

        PaymentConfirmationResult result = paymentService.confirm("payment_key", "order_test", "stored_key", 50000L);

        assertThat(result.unknown()).isTrue();
        assertThat(result.paymentResult()).isNull();
        assertThat(result.failed()).isFalse();
        assertConfirmAttemptsUseSameIdempotencyKey("stored_key", 3);
        assertThat(sleeper.durations()).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
    }

    @Test
    void confirmRetryableGatewayErrorReturnsFailureAfterRetryBudgetTest() {
        PaymentConfirmation confirmation = new PaymentConfirmation("payment_key", "order_test", "stored_key", 50000L);
        when(paymentGateway.confirm(confirmation))
                .thenThrow(new RoomEscapeException(DomainErrorCode.PAYMENT_RETRYABLE));

        PaymentConfirmationResult result = paymentService.confirm("payment_key", "order_test", "stored_key", 50000L);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureCode()).isEqualTo(DomainErrorCode.PAYMENT_RETRYABLE);
        assertConfirmAttemptsUseSameIdempotencyKey("stored_key", 3);
        assertThat(sleeper.durations()).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
    }

    @Test
    void confirmStopsRetryingWhenUnknownResultWouldExceedDeadlineTest() {
        paymentService = new PaymentService(paymentGateway, sleeper, new RetryDeadline(nanoTime),
                Duration.ofSeconds(10), Duration.ofSeconds(12));
        PaymentConfirmation confirmation = new PaymentConfirmation("payment_key", "order_test", "stored_key", 50000L);
        when(paymentGateway.confirm(confirmation))
                .thenAnswer(invocation -> {
                    nanoTime.advance(Duration.ofSeconds(10));
                    throw new RoomEscapeException(DomainErrorCode.PAYMENT_UNKNOWN);
                });

        PaymentConfirmationResult result = paymentService.confirm("payment_key", "order_test", "stored_key", 50000L);

        assertThat(result.unknown()).isTrue();
        assertConfirmAttemptsUseSameIdempotencyKey("stored_key", 1);
        assertThat(sleeper.durations()).isEmpty();
    }

    @Test
    void confirmStopsRetryingWhenRetryableFailureWouldExceedDeadlineTest() {
        paymentService = new PaymentService(paymentGateway, sleeper, new RetryDeadline(nanoTime),
                Duration.ofSeconds(10), Duration.ofSeconds(12));
        PaymentConfirmation confirmation = new PaymentConfirmation("payment_key", "order_test", "stored_key", 50000L);
        when(paymentGateway.confirm(confirmation))
                .thenAnswer(invocation -> {
                    nanoTime.advance(Duration.ofSeconds(10));
                    throw new RoomEscapeException(DomainErrorCode.PAYMENT_RETRYABLE);
                });

        PaymentConfirmationResult result = paymentService.confirm("payment_key", "order_test", "stored_key", 50000L);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureCode()).isEqualTo(DomainErrorCode.PAYMENT_RETRYABLE);
        assertConfirmAttemptsUseSameIdempotencyKey("stored_key", 1);
        assertThat(sleeper.durations()).isEmpty();
    }

    @Test
    void confirmDoesNotRetryNonRetryablePaymentErrorAndReturnsFailureTest() {
        PaymentConfirmation confirmation = new PaymentConfirmation("payment_key", "order_test", "stored_key", 50000L);
        when(paymentGateway.confirm(confirmation))
                .thenThrow(new RoomEscapeException(DomainErrorCode.PAYMENT_REJECTED));

        PaymentConfirmationResult result = paymentService.confirm("payment_key", "order_test", "stored_key", 50000L);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureCode()).isEqualTo(DomainErrorCode.PAYMENT_REJECTED);
        verify(paymentGateway, times(1)).confirm(confirmation);
        assertThat(sleeper.durations()).isEmpty();
    }

    @Test
    void confirmGatewayResultMismatchReturnsFailureTest() {
        PaymentResult result = new PaymentResult("payment_key", "other_order", "DONE", 50000L);
        when(paymentGateway.confirm(new PaymentConfirmation("payment_key", "order_test", "stored_key", 50000L)))
                .thenReturn(result);

        PaymentConfirmationResult confirmationResult = paymentService.confirm("payment_key", "order_test",
                "stored_key", 50000L);

        assertThat(confirmationResult.failed()).isTrue();
        assertThat(confirmationResult.failureCode()).isEqualTo(DomainErrorCode.PAYMENT_FAILED);
    }

    private void assertConfirmAttemptsUseSameIdempotencyKey(String idempotencyKey, int attempts) {
        ArgumentCaptor<PaymentConfirmation> captor = ArgumentCaptor.forClass(PaymentConfirmation.class);
        verify(paymentGateway, times(attempts)).confirm(captor.capture());
        List<String> idempotencyKeys = captor.getAllValues().stream()
                .map(PaymentConfirmation::idempotencyKey)
                .toList();

        assertThat(idempotencyKeys).containsOnly(idempotencyKey);
    }

    private static class FakeNanoTime implements LongSupplier {

        private long nanos;

        @Override
        public long getAsLong() {
            return nanos;
        }

        private void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }

    private static class RecordingSleeper implements roomescape.ratelimit.BackoffSleeper {

        private final List<Duration> durations = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            durations.add(duration);
        }

        private List<Duration> durations() {
            return durations;
        }
    }
}
