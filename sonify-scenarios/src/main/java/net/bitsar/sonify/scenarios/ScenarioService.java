package net.bitsar.sonify.scenarios;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import net.bitsar.sonify.scenarios.client.PaymentGatewayClient;
import net.bitsar.sonify.scenarios.client.ShippingClient;
import net.bitsar.sonify.scenarios.service.CheckoutService;
import net.bitsar.sonify.scenarios.service.CustomerCacheService;
import net.bitsar.sonify.scenarios.service.InvoiceFormatter;
import net.bitsar.sonify.scenarios.service.OrderService;
import net.bitsar.sonify.scenarios.service.QuoteMemoizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maps scenario ids onto calls into the ordinary application services.
 *
 * <p>Every one of these is a normal business operation. There is no instrumentation here and no
 * special "break the JVM" code: a scenario is a particular endpoint driven at a particular
 * concurrency, and the pathology emerges from service code that would pass review.
 *
 * <p>Nothing calls these methods from inside the application. The entry points are the two
 * controller endpoints, driven over HTTP by scenarios.sh or LoadDriver.
 */
@Service
public class ScenarioService {

    private static final Logger LOG = LoggerFactory.getLogger(ScenarioService.class);
    private static final long ORDER_CEILING = 120_000L;
    private static final long CUSTOMER_CEILING = 500L;

    private final OrderService orders;
    private final CheckoutService checkout;
    private final CustomerCacheService customers;
    private final InvoiceFormatter invoices;
    private final QuoteMemoizer quotes;
    private final PaymentGatewayClient payments;
    private final ShippingClient shipping;
    private final ScenarioRunner runner = new ScenarioRunner();

    private final Map<String, Unit> units = new LinkedHashMap<>();
    private final AtomicReference<String> active = new AtomicReference<>(null);

    public ScenarioService(OrderService orders,
                           CheckoutService checkout,
                           CustomerCacheService customers,
                           InvoiceFormatter invoices,
                           QuoteMemoizer quotes,
                           PaymentGatewayClient payments,
                           ShippingClient shipping) {
        this.orders = orders;
        this.checkout = checkout;
        this.customers = customers;
        this.invoices = invoices;
        this.quotes = quotes;
        this.payments = payments;
        this.shipping = shipping;
        register();
    }

    private void register() {
        // --- the database and the persistence layer -----------------------------------------
        units.put("db-n-plus-one", userId -> orders.listOrdersLazily("PAID"));
        units.put("db-fetch-join-paginated", userId -> orders.listOrdersJoinFetchPaginated("PAID"));
        units.put("db-n-plus-one-fixed", userId -> orders.listOrdersBatched("PAID"));
        units.put("db-transaction-spans-http", userId -> checkout.checkoutInsideTransaction(anOrder()));
        units.put("db-transaction-outside", userId -> checkout.checkoutOutsideTransaction(anOrder()));

        // --- third-party dependencies -------------------------------------------------------
        units.put("api-timeout-too-generous", userId -> shipping.quote());
        units.put("api-retry-amplification", userId -> payments.authoriseAgainstFailingGateway(1000L));
        units.put("api-breaker-blind-to-slowness", userId -> payments.authorise(1000L));
        units.put("api-payload-growth", userId -> shipping.quoteWithFullCatalogue());

        // --- caching -------------------------------------------------------------------------
        units.put("cache-healthy", userId -> customers.findCustomer(aCustomer()));
        units.put("cache-stampede", userId -> customers.revenueLeaderboard());
        units.put("cache-cold-after-deploy", userId -> customers.findCustomer(
                ThreadLocalRandom.current().nextLong(20_000) + 1));
        units.put("cache-unbounded-keys", userId -> quotes.quoteFor(anOrder()));

        // --- the concurrency model ------------------------------------------------------------
        units.put("conc-thread-per-request", userId -> shipping.quote());
        units.put("conc-block-event-loop", userId -> shipping.quote());
        units.put("conc-offload-elastic", userId -> shipping.quote());
        units.put("conc-shared-lock", userId -> invoices.formatInvoiceDate(anOrder()));

        // --- the export path --------------------------------------------------------------------
        units.put("db-export-too-large", userId -> orders.exportEverything(40_000));
    }

    private static long anOrder() {
        return 1 + ThreadLocalRandom.current().nextLong(ORDER_CEILING);
    }

    private static long aCustomer() {
        return 1 + ThreadLocalRandom.current().nextLong(CUSTOMER_CEILING);
    }

    /** Performs exactly one unit of a scenario's work, on the calling thread. */
    public void once(String scenarioId, int userId) throws Exception {
        Unit unit = units.get(scenarioId);
        if (unit == null) {
            throw new IllegalArgumentException("unknown scenario: " + scenarioId);
        }
        unit.run(userId);
    }

    /** Drives a scenario from threads inside this JVM; blind to the server's own thread pool. */
    public ScenarioOutcome runInJvm(String scenarioId, ScenarioRequest request) {
        Scenario scenario = ScenarioCatalog.find(scenarioId)
                .orElseThrow(() -> new IllegalArgumentException("unknown scenario: " + scenarioId));
        Unit unit = units.get(scenarioId);
        active.set(scenarioId);
        LOG.info("starting scenario {} with {} users for {}s",
                scenarioId, request.users(), request.duration().toSeconds());
        try {
            return runner.run(scenario, request, userId -> {
                try {
                    unit.run(userId);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
        } finally {
            active.set(null);
        }
    }

    public String activeScenario() {
        return active.get();
    }

    /** Puts the application back to a known state between scenarios. */
    public String reset() {
        int memoised = quotes.size();
        quotes.clear();
        customers.flush();
        payments.reset();
        System.gc();
        return "cleared " + memoised + " memoised quotes, the Redis keyspace and the circuit breaker";
    }

    public String status() {
        return "active=" + (active.get() == null ? "none" : active.get())
                + ", memoisedQuotes=" + quotes.size()
                + ", cacheHitRate=" + customers.hitRate()
                + ", payments=" + payments.stats()
                + ", shipping=" + shipping.stats();
    }

    public void warm() {
        customers.warm(500);
    }

    /** One unit of a scenario's work. */
    @FunctionalInterface
    private interface Unit {
        void run(int userId) throws Exception;
    }
}
