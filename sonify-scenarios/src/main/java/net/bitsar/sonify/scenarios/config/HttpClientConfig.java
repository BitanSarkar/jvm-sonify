package net.bitsar.sonify.scenarios.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP clients configured the way a careful team configures them.
 *
 * <p>Both have connect and read timeouts, because leaving them unset is a well-known footgun.
 * The difference between them is the thing worth studying: one is set in proportion to the
 * dependency's real behaviour, the other is a comfortable round number nobody thought hard about.
 *
 * <p>Built directly rather than through {@code RestTemplateBuilder}, because that builder is
 * only auto-configured for servlet applications and this module is shared with a reactive one.
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    /**
     * Timeouts in proportion to the dependency. The gateway answers in about 200ms, so two
     * seconds is ten times its worst good day and still fails fast enough to protect the pool.
     */
    @Bean
    public RestTemplate paymentRestTemplate() {
        return new RestTemplate(factory(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    /**
     * A comfortable round number, chosen so nothing would ever time out "unnecessarily".
     *
     * <p>Thirty seconds on a call whose p99 is 200ms is fifty times the real latency. It is not
     * a missing timeout and it will pass review; it simply cannot fire soon enough to stop a
     * degraded dependency consuming every request thread.
     */
    @Bean
    public RestTemplate shippingRestTemplate() {
        return new RestTemplate(factory(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    private static SimpleClientHttpRequestFactory factory(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return factory;
    }
}
