package roomescape.ratelimit;

import java.util.function.LongSupplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({RateLimitProperties.class, OutboundRateLimitProperties.class,
        TossCircuitBreakerProperties.class})
public class RateLimitConfig {

    @Bean
    public LongSupplier nanoTimeSupplier() {
        return System::nanoTime;
    }

    @Bean
    public RetryDeadline retryDeadline(LongSupplier nanoTime) {
        return new RetryDeadline(nanoTime);
    }
}
