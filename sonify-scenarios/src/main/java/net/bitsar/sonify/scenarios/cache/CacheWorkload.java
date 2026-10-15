package net.bitsar.sonify.scenarios.cache;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Real Redis in front of real PostgreSQL, including the ways that combination goes wrong.
 *
 * <p>The cache hit path exists as much as the failures do: without hearing a healthy system there
 * is nothing to compare an unhealthy one against.
 */
@Component
public class CacheWorkload {

    private static final Logger LOG = LoggerFactory.getLogger(CacheWorkload.class);
    private static final String PREFIX = "sonify:customer:";
    private static final String HOT_KEY = "sonify:hot:leaderboard";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration HOT_TTL = Duration.ofSeconds(2);

    private final StringRedisTemplate redis;
    private final DataSource dataSource;

    /** A local cache with no eviction, which is the in-process version of the same mistake. */
    private final Map<String, String> unboundedLocal = new ConcurrentHashMap<>();

    /** The recompute guard that turns a cache miss into a queue. */
    private final Object stampedeGuard = new Object();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public CacheWorkload(StringRedisTemplate redis, DataSource dataSource) {
        this.redis = redis;
        this.dataSource = dataSource;
    }

    /** Look in Redis, fall through to PostgreSQL, populate. The ordinary healthy path. */
    public void cacheHitPath(int userId) throws SQLException {
        long customerId = 1 + ThreadLocalRandom.current().nextLong(500);
        String key = PREFIX + customerId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return;
        }
        misses.incrementAndGet();
        redis.opsForValue().set(key, loadCustomer(customerId), TTL);
    }

    /**
     * A hot key with a short time to live, recomputed under a lock.
     *
     * <p>When it expires every in-flight request finds it missing at the same moment and queues
     * on the guard, so one expiry becomes seconds of contention. The lock is the anti-pattern:
     * without it the database gets stampeded, with it the application does.
     */
    public void cacheStampede(int userId) throws SQLException {
        String cached = redis.opsForValue().get(HOT_KEY);
        if (cached != null) {
            hits.incrementAndGet();
            return;
        }
        misses.incrementAndGet();
        synchronized (stampedeGuard) {
            // Double-checked, exactly as it is usually written, and still a queue under load
            // because everyone already missed before anyone acquired the lock.
            String second = redis.opsForValue().get(HOT_KEY);
            if (second == null) {
                redis.opsForValue().set(HOT_KEY, expensiveLeaderboard(), HOT_TTL);
            }
        }
    }

    /** Every request misses, because the cache was just flushed. */
    public void coldCache(int userId) throws SQLException {
        long customerId = 1 + ThreadLocalRandom.current().nextLong(20_000);
        String key = PREFIX + "cold:" + customerId;
        if (redis.opsForValue().get(key) != null) {
            hits.incrementAndGet();
            return;
        }
        misses.incrementAndGet();
        redis.opsForValue().set(key, loadCustomer(customerId), TTL);
    }

    /** A local map used as a cache, keyed by something unbounded. Nothing ever leaves it. */
    public void unboundedLocalCache(int userId) throws SQLException {
        // Keyed per request, which is the bug: the key space is unbounded, so the map is too.
        String sessionKey = "session-" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        unboundedLocal.put(sessionKey, loadCustomer(1 + ThreadLocalRandom.current().nextLong(500)));
    }

    public int localCacheSize() {
        return unboundedLocal.size();
    }

    /** Clears everything this workload created, so a run starts from a known state. */
    public void reset() {
        unboundedLocal.clear();
        try {
            var keys = redis.keys("sonify:*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException unavailable) {
            LOG.warn("could not clear Redis: {}", unavailable.getMessage());
        }
        hits.set(0);
        misses.set(0);
    }

    public String hitRate() {
        long hit = hits.get();
        long total = hit + misses.get();
        return total == 0 ? "no requests yet" : Math.round(100d * hit / total) + "% of " + total;
    }

    /** Warms the keys the healthy path reads, so it genuinely is a hit path. */
    public void warm() throws SQLException {
        for (long customerId = 1; customerId <= 500; customerId++) {
            redis.opsForValue().set(PREFIX + customerId, loadCustomer(customerId), TTL);
        }
        LOG.info("warmed 500 customer keys");
    }

    private String loadCustomer(long customerId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT email, full_name, country FROM customer WHERE id = ?")) {
            statement.setLong(1, customerId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return "{}";
                }
                return "{\"email\":\"" + rows.getString(1) + "\",\"name\":\"" + rows.getString(2)
                        + "\",\"country\":\"" + rows.getString(3) + "\"}";
            }
        }
    }

    /** The kind of query people put behind a hot cache key, because it is too slow to repeat. */
    private String expensiveLeaderboard() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT c.country, count(*), sum(o.total_cents) FROM customer_order o "
                             + "JOIN customer c ON c.id = o.customer_id GROUP BY c.country ORDER BY 3 DESC")) {
            StringBuilder out = new StringBuilder("[");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.append('{').append(rows.getString(1)).append(':').append(rows.getLong(3)).append('}');
                }
            }
            return out.append(']').toString();
        }
    }
}
