package roomescape.ratelimit;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

public class TossCircuitBreakerInterceptor implements ClientHttpRequestInterceptor {

    private final TossCircuitBreaker circuitBreaker;

    public TossCircuitBreakerInterceptor(TossCircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!circuitBreaker.tryAcquirePermission()) {
            throw new TossCircuitBreakerOpenException("Toss circuit breaker is open");
        }
        try {
            ClientHttpResponse response = execution.execute(request, body);
            return record(response);
        } catch (IOException e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }

    private ClientHttpResponse record(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            circuitBreaker.recordIgnored();
            return response;
        }
        if (response.getStatusCode().is5xxServerError()) {
            circuitBreaker.recordFailure();
            return response;
        }
        return new CircuitBreakerRecordingResponse(response, circuitBreaker);
    }

    private static class CircuitBreakerRecordingResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final TossCircuitBreaker circuitBreaker;

        private InputStream body;
        private boolean recorded;

        private CircuitBreakerRecordingResponse(ClientHttpResponse delegate, TossCircuitBreaker circuitBreaker) {
            this.delegate = delegate;
            this.circuitBreaker = circuitBreaker;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
            recordSuccess();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                try {
                    body = new FailureRecordingInputStream(delegate.getBody());
                } catch (IOException e) {
                    recordFailure();
                    throw e;
                }
            }
            return body;
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        private void recordSuccess() {
            if (!recorded) {
                recorded = true;
                circuitBreaker.recordSuccess();
            }
        }

        private void recordFailure() {
            if (!recorded) {
                recorded = true;
                circuitBreaker.recordFailure();
            }
        }

        private class FailureRecordingInputStream extends FilterInputStream {

            private FailureRecordingInputStream(InputStream in) {
                super(in);
            }

            @Override
            public int read() throws IOException {
                try {
                    return super.read();
                } catch (IOException e) {
                    recordFailure();
                    throw e;
                }
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                try {
                    return super.read(bytes, offset, length);
                } catch (IOException e) {
                    recordFailure();
                    throw e;
                }
            }
        }
    }
}
