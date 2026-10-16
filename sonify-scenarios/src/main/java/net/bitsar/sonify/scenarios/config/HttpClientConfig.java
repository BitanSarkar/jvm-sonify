package net.bitsar.sonify.scenarios.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP clients configured the way a careful team configures them.
 *
 * <p>Both have connect and read timeouts, because leaving them unset is a well-known footgun and
 * most teams have been bitten once. The difference between them is the thing worth studying: one
 * timeout is set close to the dependency's real behaviour, the other is set to a comfortable
 * round number that nobody thought hard about.
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    /**
     * Timeouts in proportion to the dependency. The gateway answers in ~200ms, so two seconds is
     * ten times its worst good day and still fails fast enough to protect the thread pool.
     */
    @Bean
    public RestTemplate paymentRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(1))
                .readTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * A comfortable round number, chosen so that nothing would ever time out "unnecessarily".
     *
     * <p>Thirty seconds on a call whose p99 is 200ms is fifty times the real latency. It is not
     * a missing timeout, and it will pass review; it simply cannot fire soon enough to stop a
     * degraded dependency consuming every request thread. This is the more common production
     * incident, and the more interesting one.
     */
    @Bean
    public RestTemplate shippingRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }
}
