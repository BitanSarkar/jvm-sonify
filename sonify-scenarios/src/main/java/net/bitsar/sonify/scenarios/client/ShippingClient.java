package net.bitsar.sonify.scenarios.client;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * A shipping quote client. Also properly configured, also retried, and still a liability for a
 * reason that has nothing to do with resilience: its timeout is fifty times the call's real
 * latency, and its response grew after a vendor added a field.
 */
@Component
public class ShippingClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final AtomicLong bytesParsed = new AtomicLong();

    public ShippingClient(@Qualifier("shippingRestTemplate") RestTemplate restTemplate,
                          @Value("${scenarios.upstream.base-url:http://localhost:8090}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /** A dependency that has become slow, called by a client whose timeout is far too generous. */
    public String quote() {
        String body = restTemplate.getForObject(baseUrl + "/slow?ms=5000", String.class);
        return body == null ? "{}" : body;
    }

    /** The same endpoint after the vendor's response grew from kilobytes to megabytes. */
    public int quoteWithFullCatalogue() {
        String body = restTemplate.getForObject(baseUrl + "/huge?mb=5", String.class);
        if (body == null) {
            return 0;
        }
        bytesParsed.addAndGet(body.length());
        return parse(body);
    }

    /** Walks the payload the way a deserialiser would, allocating as it goes. */
    private static int parse(String body) {
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
        return items;
    }

    public String stats() {
        return (bytesParsed.get() / 1048576) + "MB parsed";
    }
}
