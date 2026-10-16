package net.bitsar.sonify.scenarios.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * Memoises shipping quotes so the same request is not priced twice.
 *
 * <p>A {@link ConcurrentHashMap}, thread safe, no locking, computeIfAbsent for atomicity. It is
 * the obvious way to add a small cache and it reviews cleanly.
 *
 * <p>The flaw is in the key, not the structure. The key includes a quote reference, which is
 * unique per request, so no entry is ever read twice and nothing is ever evicted. The map grows
 * for the lifetime of the process. Heap climbs steadily, collections run more often and reclaim
 * less each time, and the application dies days after the change shipped, which is what makes
 * this one so hard to attribute.
 *
 * <p>A bounded cache with an eviction policy, or a key that actually repeats, fixes it.
 */
@Service
public class QuoteMemoizer {

    private final Map<String, String> quotes = new ConcurrentHashMap<>();

    public String quoteFor(long orderId) {
        // The quote reference is unique per request, so this key never repeats.
        String key = orderId + ":" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        return quotes.computeIfAbsent(key, missing -> "quote-" + missing.hashCode()
                + "-" + "x".repeat(512));
    }

    public int size() {
        return quotes.size();
    }

    public void clear() {
        quotes.clear();
    }
}
