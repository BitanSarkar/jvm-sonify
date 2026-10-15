package net.bitsar.sonify.scenarios.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Real queries against real PostgreSQL through a real HikariCP pool.
 *
 * <p>Nothing here is simulated with sleeps: the pool genuinely runs out of connections, the
 * N+1 pattern genuinely issues one query per row, and the unbounded fetch genuinely materialises
 * every row it asks for. That matters because the JVM signature of waiting for a connection is
 * quite different from the signature of waiting for a socket, and only real code produces it.
 */
@Component
public class DatabaseWorkload {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseWorkload.class);

    /** Bounded so that one careless scenario cannot take the whole demo down with an OOM. */
    private static final int MAX_CONCURRENT_BIG_FETCHES = 4;
    private static final int UNBOUNDED_ROWS = 40_000;

    private final DataSource dataSource;
    private final Semaphore bigFetches = new Semaphore(MAX_CONCURRENT_BIG_FETCHES);

    /** Connections deliberately never returned, so the pool drains exactly as a real leak does. */
    private final List<Connection> leaked = new CopyOnWriteArrayList<>();

    public DatabaseWorkload(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Holds a pooled connection for the length of a realistic piece of work.
     *
     * <p>With more users than the pool has connections, the surplus queue inside Hikari, which
     * parks them rather than blocking on a monitor. That is why this scenario shows up as waiting
     * threads and flat CPU instead of contention.
     */
    public void poolExhaustion(int userId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            // A deliberately unindexed aggregate, so the database really does take a while.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*), sum(total_cents) FROM customer_order WHERE status = ? AND total_cents > ?")) {
                statement.setString(1, pickStatus(userId));
                statement.setLong(2, ThreadLocalRandom.current().nextLong(100_000));
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                }
            }
        }
    }

    /** One query for a page of orders, then one more for every order in it. */
    public void nPlusOne(int userId) throws SQLException {
        long customerId = 1 + ThreadLocalRandom.current().nextLong(20_000);
        try (Connection connection = dataSource.getConnection()) {
            List<Long> orderIds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM customer_order WHERE customer_id = ? LIMIT 40")) {
                statement.setLong(1, customerId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        orderIds.add(rows.getLong(1));
                    }
                }
            }
            // The N+1: a separate round trip for every single order.
            List<String> descriptions = new ArrayList<>();
            for (Long orderId : orderIds) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT sku, description FROM order_line WHERE order_id = ?")) {
                    statement.setLong(1, orderId);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            descriptions.add(rows.getString(1) + rows.getString(2));
                        }
                    }
                }
            }
            consume(descriptions);
        }
    }

    /** A query with no sensible limit, materialised into memory the way an ORM would. */
    public void unboundedResult(int userId) throws SQLException, InterruptedException {
        bigFetches.acquire();
        try (Connection connection = dataSource.getConnection()) {
            List<String> everything = new ArrayList<>(UNBOUNDED_ROWS);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT sku, description, price_cents FROM order_line LIMIT " + UNBOUNDED_ROWS)) {
                statement.setFetchSize(0);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        everything.add(rows.getString(1) + '|' + rows.getString(2) + '|' + rows.getLong(3));
                    }
                }
            }
            consume(everything);
        } finally {
            bigFetches.release();
        }
    }

    /**
     * Takes a connection and never gives it back.
     *
     * <p>Capped at the pool size so the pool drains and stays drained, which is the failure mode,
     * without the demo becoming unrecoverable: {@link #releaseLeaks()} puts them all back.
     */
    public void connectionLeak(int userId) throws SQLException {
        if (leaked.size() < 32) {
            Connection stolen = dataSource.getConnection();
            try (PreparedStatement statement = stolen.prepareStatement("SELECT 1")) {
                statement.executeQuery().close();
            }
            leaked.add(stolen);
            return;
        }
        // Once the pool is dry, every further request simply waits for a connection forever.
        poolExhaustion(userId);
    }

    /** Hands every leaked connection back, so the demo can be run again. */
    public int releaseLeaks() {
        int released = 0;
        for (Connection connection : leaked) {
            try {
                connection.close();
                released++;
            } catch (SQLException ignored) {
                // Already gone; nothing useful to do.
            }
        }
        leaked.clear();
        if (released > 0) {
            LOG.info("released {} deliberately leaked connections", released);
        }
        return released;
    }

    public int leakedCount() {
        return leaked.size();
    }

    private static String pickStatus(int userId) {
        return switch (userId % 3) {
            case 0 -> "NEW";
            case 1 -> "PAID";
            default -> "SHIPPED";
        };
    }

    /** Touches the data so neither the JIT nor the reader can dismiss it as dead. */
    private static void consume(List<String> values) {
        long hash = 0;
        for (String value : values) {
            hash += value.length();
        }
        if (hash == Long.MIN_VALUE) {
            LOG.trace("unreachable");
        }
    }
}
