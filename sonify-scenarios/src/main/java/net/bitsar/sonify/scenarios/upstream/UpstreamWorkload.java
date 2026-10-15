package net.bitsar.sonify.scenarios.upstream;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Calls the upstream process over real HTTP, including the ways clients get it wrong.
 *
 * <p>Two clients are kept deliberately: one configured sensibly, and one with no read timeout at
 * all. The second is the common production bug, and its signature is distinctive because the
 * threads it consumes never come back on their own.
 */
@Component
public class UpstreamWorkload {

    private static final Logger LOG = LoggerFactory.getLogger(UpstreamWorkload.class);
    private static final int RETRIES = 3;

    private final String baseUrl;
    private final HttpClient sensible;
    private final HttpClient noTimeout;
    private final AtomicLong upstreamCalls = new AtomicLong();
    private final AtomicLong upstreamFailures = new AtomicLong();

    public UpstreamWorkload(@Value("${scenarios.upstream.base-url:http://localhost:8090}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.sensible = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        // No connectTimeout and, more importantly, no per-request timeout below.
        this.noTimeout = HttpClient.newHttpClient();
    }

    /** A dependency that has become slow, called the normal blocking way. */
    public void slowUpstream(int userId) throws IOException, InterruptedException {
        get(sensible, "/slow?ms=2000", Duration.ofSeconds(10));
    }

    /** A shorter blocking call, for showing a thread pool fill up gradually. */
    public void slowUpstreamShort(int userId) throws IOException, InterruptedException {
        get(sensible, "/slow?ms=300", Duration.ofSeconds(10));
    }

    /**
     * Retries a failing dependency immediately, three times, with no backoff.
     *
     * <p>Each user request becomes up to four upstream requests, so a dependency already failing
     * under load receives four times the traffic. This is how a wobble becomes an outage.
     */
    public void retryStorm(int userId) throws InterruptedException {
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            try {
                HttpResponse<String> response = get(sensible, "/flaky?failPercent=70&ms=120",
                        Duration.ofSeconds(5));
                if (response.statusCode() < 500) {
                    return;
                }
            } catch (IOException retryable) {
                upstreamFailures.incrementAndGet();
            }
            // No backoff at all, which is the point.
        }
    }

    /** A client with no read timeout, talking to something that never answers. */
    public void missingTimeout(int userId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/blackhole"))
                .GET()
                .build();
        upstreamCalls.incrementAndGet();
        noTimeout.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Megabytes of JSON, parsed on every request. */
    public void hugePayload(int userId) throws IOException, InterruptedException {
        HttpResponse<String> response = get(sensible, "/huge?mb=5", Duration.ofSeconds(30));
        parse(response.body());
    }

    public String stats() {
        return upstreamCalls.get() + " calls, " + upstreamFailures.get() + " failures";
    }

    private HttpResponse<String> get(HttpClient client, String path, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (timeout != null) {
            builder.timeout(timeout);
        }
        upstreamCalls.incrementAndGet();
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            upstreamFailures.incrementAndGet();
            throw failure;
        }
    }

    /** Walks the payload the way a real deserialiser would, allocating as it goes. */
    private static void parse(String body) {
        int items = 0;
        char[] characters = body.toCharArray();
        StringBuilder field = new StringBuilder(192);
        for (char character : characters) {
            if (character == '{') {
                items++;
                field.setLength(0);
            } else if (character != '}') {
                field.append(character);
            }
        }
        if (items == Integer.MIN_VALUE) {
            LOG.trace("unreachable");
        }
    }
}
