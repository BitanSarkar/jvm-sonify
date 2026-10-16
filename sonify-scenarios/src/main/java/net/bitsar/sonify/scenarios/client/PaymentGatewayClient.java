package net.bitsar.sonify.scenarios.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * A payment gateway client with the defences a production client usually has: a bounded timeout,
 * bounded retries with exponential backoff and jitter, and a circuit breaker that opens after
 * repeated failures.
 *
 * <p>None of this is window dressing, and all of it works. The scenarios built on this client are
 * not about missing resilience; they are about what resilient code still cannot do. A circuit
 * breaker protects the dependency and your error rate. It does not give you back the request
 * threads that are already waiting, and it does nothing at all when the dependency is merely
 * slow rather than failing.
 */
@Component
public class PaymentGatewayClient {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(100);
    private static final int BREAKER_THRESHOLD = 20;
    private static final Duration BREAKER_RESET = Duration.ofSeconds(5);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong breakerOpenedAt = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong shortCircuited = new AtomicLong();

    public PaymentGatewayClient(@Qualifier("paymentRestTemplate") RestTemplate restTemplate,
                                @Value("${scenarios.upstream.base-url:http://localhost:8090}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /** Authorises against a gateway that is failing, which is what a breaker is built for. */
    public String authoriseAgainstFailingGateway(long amountCents) {
        return call("/flaky?failPercent=70&ms=150");
    }

    /**
     * Authorises against a gateway that is merely slow.
     *
     * <p>Worth doing deliberately, because it is the case the breaker cannot help with. Nothing
     * fails, so the failure counter never rises, so the breaker never opens, and every request
     * thread waits out the full two seconds. Resilience against errors is not resilience
     * against latency.
     */
    public String authorise(long amountCents) {
        return call("/slow?ms=2000");
    }

    private String call(String path) {
        if (breakerIsOpen()) {
            shortCircuited.incrementAndGet();
            return "declined: circuit open";
        }
        RestClientException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                calls.incrementAndGet();
                String body = restTemplate.getForObject(baseUrl + path, String.class);
                consecutiveFailures.set(0);
                return body == null ? "{}" : body;
            } catch (RestClientException failure) {
                last = failure;
                recordFailure();
                if (attempt < MAX_ATTEMPTS) {
                    backOff(attempt);
                }
            }
        }
        LOG.debug("payment authorisation failed after {} attempts: {}", MAX_ATTEMPTS,
                last == null ? "unknown" : last.getMessage());
        return "declined: upstream unavailable";
    }

    /** Exponential backoff with jitter, so retries do not synchronise into a second wave. */
    private void backOff(int attempt) {
        long millis = BASE_BACKOFF.toMillis() * (1L << (attempt - 1));
        long jittered = millis / 2 + ThreadLocalRandom.current().nextLong(millis);
        try {
            Thread.sleep(jittered);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= BREAKER_THRESHOLD) {
            breakerOpenedAt.compareAndSet(0, System.currentTimeMillis());
        }
    }

    private boolean breakerIsOpen() {
        long openedAt = breakerOpenedAt.get();
        if (openedAt == 0) {
            return false;
        }
        if (System.currentTimeMillis() - openedAt > BREAKER_RESET.toMillis()) {
            breakerOpenedAt.set(0);
            consecutiveFailures.set(0);
            return false;
        }
        return true;
    }

    public String stats() {
        return calls.get() + " calls, " + shortCircuited.get() + " short-circuited, breaker "
                + (breakerIsOpen() ? "OPEN" : "closed");
    }

    public void reset() {
        consecutiveFailures.set(0);
        breakerOpenedAt.set(0);
        calls.set(0);
        shortCircuited.set(0);
    }
}
