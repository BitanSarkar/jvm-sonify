package net.bitsar.sonify.scenarios.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Memoises shipping quotes so the same request is not priced twice.
 *
 * <p>A {@link ConcurrentHashMap}, thread safe, no locking, {@code computeIfAbsent} for atomicity.
 * It is the obvious way to add a small cache and it reviews cleanly.
 *
 * <p>The flaw is in the key, not the structure. The key includes a quote reference that is unique
 * per request, so no entry is ever read twice and nothing is ever evicted. The map grows for the
 * lifetime of the process, heap climbs steadily, collections reclaim less each time, and the
 * application dies days after the change shipped.
 *
 * <p>In production that takes days. Here, thirty concurrent users fill a 512MB heap in seconds
 * and the JVM goes down with an OutOfMemoryError, which demonstrates nothing except that the
 * demo is over. Growth therefore stops at a budgeted fraction of the heap: enough that the drone
 * climbs and collections visibly stop reclaiming, and not so much that there is nothing left to
 * listen to. The real version has no such mercy.
 */
@Service
public class QuoteMemoizer {

    private static final Logger LOG = LoggerFactory.getLogger(QuoteMemoizer.class);
    private static final int ENTRY_BYTES = 640;

    private final Map<String, String> quotes = new ConcurrentHashMap<>();
    private final long budgetBytes = Runtime.getRuntime().maxMemory() / 3;
    private final AtomicLong retainedBytes = new AtomicLong();
    private volatile boolean budgetReported;

    public String quoteFor(long orderId) {
        if (retainedBytes.get() >= budgetBytes) {
            if (!budgetReported) {
                budgetReported = true;
                LOG.warn("memoiser reached its {}MB demo budget with {} entries; "
                                + "a real one would keep going until the heap was gone",
                        budgetBytes / 1048576, quotes.size());
            }
            // Still does the work, just stops retaining it.
            return "quote-" + orderId;
        }
        // The quote reference is unique per request, so this key never repeats.
        String key = orderId + ":" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        String value = quotes.computeIfAbsent(key,
                missing -> "quote-" + missing.hashCode() + "-" + "x".repeat(512));
        retainedBytes.addAndGet(ENTRY_BYTES);
        return value;
    }

    public int size() {
        return quotes.size();
    }

    public void clear() {
        quotes.clear();
        retainedBytes.set(0);
        budgetReported = false;
    }
}
