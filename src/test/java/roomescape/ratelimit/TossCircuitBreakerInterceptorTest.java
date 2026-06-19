package roomescape.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class TossCircuitBreakerInterceptorTest {

    @Test
    void records5xxAsFailureAndOpensTest() throws IOException {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreaker circuitBreaker = new TossCircuitBreaker(properties(), now::get);
        TossCircuitBreakerInterceptor interceptor = new TossCircuitBreakerInterceptor(circuitBreaker);
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.com"));
        SequenceExecution execution = new SequenceExecution(
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK));

        for (int i = 0; i < 10; i++) {
            interceptor.intercept(request, new byte[0], execution).close();
        }

        assertThat(circuitBreaker.state()).isEqualTo(TossCircuitBreaker.State.OPEN);
    }

    @Test
    void ignores429ForFailureRateTest() throws IOException {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreaker circuitBreaker = new TossCircuitBreaker(properties(), now::get);
        TossCircuitBreakerInterceptor interceptor = new TossCircuitBreakerInterceptor(circuitBreaker);
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.com"));
        SequenceExecution execution = new SequenceExecution(
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.TOO_MANY_REQUESTS));

        for (int i = 0; i < 10; i++) {
            interceptor.intercept(request, new byte[0], execution).close();
        }

        assertThat(circuitBreaker.state()).isEqualTo(TossCircuitBreaker.State.CLOSED);
    }

    @Test
    void rejectsBeforeExecutingRequestWhenOpenTest() throws IOException {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreaker circuitBreaker = new TossCircuitBreaker(properties(), now::get);
        TossCircuitBreakerInterceptor interceptor = new TossCircuitBreakerInterceptor(circuitBreaker);
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.com"));
        SequenceExecution execution = new SequenceExecution(
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK),
                response(HttpStatus.INTERNAL_SERVER_ERROR), response(HttpStatus.OK));

        for (int i = 0; i < 10; i++) {
            interceptor.intercept(request, new byte[0], execution).close();
        }

        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], execution))
                .isInstanceOf(TossCircuitBreakerOpenException.class);
        assertThat(execution.count()).isEqualTo(10);
    }

    @Test
    void recordsBodyReadFailureAsCircuitFailureTest() throws IOException {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreakerProperties properties = new TossCircuitBreakerProperties(true, 20, 1, 1D,
                Duration.ofSeconds(10), 3, 2);
        TossCircuitBreaker circuitBreaker = new TossCircuitBreaker(properties, now::get);
        TossCircuitBreakerInterceptor interceptor = new TossCircuitBreakerInterceptor(circuitBreaker);
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.com"));
        SequenceExecution execution = new SequenceExecution(new BodyFailureResponse());

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        assertThatThrownBy(() -> response.getBody().read())
                .isInstanceOf(IOException.class);
        assertThat(circuitBreaker.state()).isEqualTo(TossCircuitBreaker.State.OPEN);
    }

    @Test
    void recordsGetBodyFailureAsCircuitFailureTest() throws IOException {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreakerProperties properties = new TossCircuitBreakerProperties(true, 20, 1, 1D,
                Duration.ofSeconds(10), 3, 2);
        TossCircuitBreaker circuitBreaker = new TossCircuitBreaker(properties, now::get);
        TossCircuitBreakerInterceptor interceptor = new TossCircuitBreakerInterceptor(circuitBreaker);
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.com"));
        SequenceExecution execution = new SequenceExecution(new BodyAccessFailureResponse());

        ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

        assertThatThrownBy(response::getBody)
                .isInstanceOf(IOException.class);
        response.close();
        assertThat(circuitBreaker.state()).isEqualTo(TossCircuitBreaker.State.OPEN);
    }

    @Test
    void halfOpen429DoesNotExhaustProbePermitsTest() throws IOException {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreakerProperties properties = new TossCircuitBreakerProperties(true, 2, 2, 50D,
                Duration.ofSeconds(10), 3, 1);
        TossCircuitBreaker circuitBreaker = new TossCircuitBreaker(properties, now::get);
        TossCircuitBreakerInterceptor interceptor = new TossCircuitBreakerInterceptor(circuitBreaker);
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.com"));
        SequenceExecution execution = new SequenceExecution(
                response(HttpStatus.INTERNAL_SERVER_ERROR),
                response(HttpStatus.OK),
                response(HttpStatus.TOO_MANY_REQUESTS),
                response(HttpStatus.TOO_MANY_REQUESTS),
                response(HttpStatus.TOO_MANY_REQUESTS),
                response(HttpStatus.OK));
        interceptor.intercept(request, new byte[0], execution).close();
        interceptor.intercept(request, new byte[0], execution).close();
        now.addAndGet(Duration.ofSeconds(10).toNanos());

        interceptor.intercept(request, new byte[0], execution).close();
        interceptor.intercept(request, new byte[0], execution).close();
        interceptor.intercept(request, new byte[0], execution).close();
        interceptor.intercept(request, new byte[0], execution).close();

        assertThat(execution.count()).isEqualTo(6);
        assertThat(circuitBreaker.state()).isEqualTo(TossCircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenClosesWhenSuccessThresholdMetAndRemainingProbeIs429Test() throws IOException {
        AtomicLong now = new AtomicLong(0L);
        TossCircuitBreakerProperties properties = new TossCircuitBreakerProperties(true, 2, 2, 50D,
                Duration.ofSeconds(10), 3, 2);
        TossCircuitBreaker circuitBreaker = new TossCircuitBreaker(properties, now::get);
        TossCircuitBreakerInterceptor interceptor = new TossCircuitBreakerInterceptor(circuitBreaker);
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("https://example.com"));
        SequenceExecution execution = new SequenceExecution(
                response(HttpStatus.INTERNAL_SERVER_ERROR),
                response(HttpStatus.OK),
                response(HttpStatus.OK),
                response(HttpStatus.OK),
                response(HttpStatus.TOO_MANY_REQUESTS));
        interceptor.intercept(request, new byte[0], execution).close();
        interceptor.intercept(request, new byte[0], execution).close();
        now.addAndGet(Duration.ofSeconds(10).toNanos());

        ClientHttpResponse firstProbe = interceptor.intercept(request, new byte[0], execution);
        ClientHttpResponse secondProbe = interceptor.intercept(request, new byte[0], execution);
        ClientHttpResponse thirdProbe = interceptor.intercept(request, new byte[0], execution);
        firstProbe.close();
        secondProbe.close();
        thirdProbe.close();

        assertThat(circuitBreaker.state()).isEqualTo(TossCircuitBreaker.State.CLOSED);
    }

    private TossCircuitBreakerProperties properties() {
        return new TossCircuitBreakerProperties(true, 20, 10, 50D, Duration.ofSeconds(10), 3, 2);
    }

    private static ClientHttpResponse response(HttpStatus status) {
        return new MockClientHttpResponse(new byte[0], status);
    }

    private static class SequenceExecution implements ClientHttpRequestExecution {

        private final ClientHttpResponse[] responses;
        private int count;

        private SequenceExecution(ClientHttpResponse... responses) {
            this.responses = responses;
        }

        @Override
        public ClientHttpResponse execute(org.springframework.http.HttpRequest request, byte[] body) {
            return responses[count++];
        }

        private int count() {
            return count;
        }
    }

    private static class BodyFailureResponse extends MockClientHttpResponse {

        private BodyFailureResponse() {
            super(new byte[0], HttpStatus.OK);
        }

        @Override
        public InputStream getBody() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("body read failed");
                }
            };
        }
    }

    private static class BodyAccessFailureResponse extends MockClientHttpResponse {

        private BodyAccessFailureResponse() {
            super(new byte[0], HttpStatus.OK);
        }

        @Override
        public InputStream getBody() throws IOException {
            throw new IOException("body access failed");
        }
    }
}
