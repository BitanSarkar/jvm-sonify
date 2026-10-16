package net.bitsar.sonify.scenarios.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import net.bitsar.sonify.scenarios.repository.CustomerRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Redis in front of the database, including the ways that goes wrong. */
@Service
public class CustomerCacheService {

    private static final String KEY = "sonify:customer:";
    private static final String HOT_KEY = "sonify:hot:revenue";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration HOT_TTL = Duration.ofSeconds(2);

    /** The recompute guard. Protects the database and queues the application instead. */
    private final Object recomputeLock = new Object();

    private final StringRedisTemplate redis;
    private final CustomerRepository customers;
    private final OrderService orders;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public CustomerCacheService(StringRedisTemplate redis, CustomerRepository customers, OrderService orders) {
        this.redis = redis;
        this.customers = customers;
        this.orders = orders;
    }

    /** Look aside, fall through, populate. The ordinary healthy path. */
    public String findCustomer(long customerId) {
        String cached = redis.opsForValue().get(KEY + customerId);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        String loaded = load(customerId);
        redis.opsForValue().set(KEY + customerId, loaded, TTL);
        return loaded;
    }

    /**
     * A hot key with a short time to live, recomputed under a lock.
     *
     * <p>When it expires every in-flight request misses at the same moment and queues on the
     * guard. Double-checked locking does not help: everyone had already missed before anyone
     * acquired the lock.
     */
    public String revenueLeaderboard() {
        String cached = redis.opsForValue().get(HOT_KEY);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        synchronized (recomputeLock) {
            String second = redis.opsForValue().get(HOT_KEY);
            if (second != null) {
                return second;
            }
            String computed = String.valueOf(orders.revenueByCountry());
            redis.opsForValue().set(HOT_KEY, computed, HOT_TTL);
            return computed;
        }
    }

    @Transactional(readOnly = true)
    public String load(long customerId) {
        return customers.findById(customerId)
                .map(customer -> "{\"email\":\"" + customer.getEmail() + "\",\"country\":\""
                        + customer.getCountry() + "\"}")
                .orElse("{}");
    }

    public void warm(int count) {
        for (long id = 1; id <= count; id++) {
            redis.opsForValue().set(KEY + id, load(id), TTL);
        }
    }

    public void flush() {
        var keys = redis.keys("sonify:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        hits.set(0);
        misses.set(0);
    }

    public String hitRate() {
        long hit = hits.get();
        long total = hit + misses.get();
        return total == 0 ? "no requests yet" : Math.round(100d * hit / total) + "% of " + total;
    }
}
