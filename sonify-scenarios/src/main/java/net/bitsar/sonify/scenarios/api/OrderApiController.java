package net.bitsar.sonify.scenarios.api;

import net.bitsar.sonify.scenarios.service.CheckoutService;
import net.bitsar.sonify.scenarios.service.CustomerCacheService;
import net.bitsar.sonify.scenarios.service.InvoiceFormatter;
import net.bitsar.sonify.scenarios.service.OrderService;
import net.bitsar.sonify.scenarios.service.QuoteMemoizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application's own API, as thin as a controller should be.
 *
 * <p>Nothing here is instrumented or special. These are the endpoints the business would have,
 * and the scenarios drive them; the pathologies live in the services behind, in code that reads
 * like code anybody would write.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api")
public class OrderApiController {

    private final OrderService orders;
    private final CheckoutService checkout;
    private final CustomerCacheService customers;
    private final InvoiceFormatter invoices;
    private final QuoteMemoizer quotes;

    public OrderApiController(OrderService orders,
                              CheckoutService checkout,
                              CustomerCacheService customers,
                              InvoiceFormatter invoices,
                              QuoteMemoizer quotes) {
        this.orders = orders;
        this.checkout = checkout;
        this.customers = customers;
        this.invoices = invoices;
        this.quotes = quotes;
    }

    /**
     * A listing page, with the fetch strategy as a parameter.
     *
     * <p>All three return the same answer and differ enormously in cost: {@code lazy} is the
     * N+1, {@code join} is the pagination trap that is worse than the N+1, and {@code batched}
     * is the fix that works.
     */
    @GetMapping("/orders")
    public String listOrders(@RequestParam(name = "status", defaultValue = "PAID") String status,
                             @RequestParam(name = "fetch", defaultValue = "lazy") String fetch) {
        int lines = switch (fetch) {
            case "join" -> orders.listOrdersJoinFetchPaginated(status);
            case "batched" -> orders.listOrdersBatched(status);
            default -> orders.listOrdersLazily(status);
        };
        return "{\"lines\":" + lines + ",\"fetch\":\"" + fetch + "\"}";
    }

    @GetMapping("/orders/export")
    public String export(@RequestParam(name = "limit", defaultValue = "40000") int limit) {
        return "{\"exported\":" + orders.exportEverything(limit) + "}";
    }

    @GetMapping("/orders/{id}/checkout")
    public String checkout(@PathVariable("id") long id,
                           @RequestParam(name = "tx", defaultValue = "inside") String tx) {
        return "inside".equals(tx)
                ? checkout.checkoutInsideTransaction(id)
                : checkout.checkoutOutsideTransaction(id);
    }

    @GetMapping("/customers/{id}")
    public String customer(@PathVariable("id") long id) {
        return customers.findCustomer(id);
    }

    @GetMapping("/reports/revenue")
    public String revenue() {
        return customers.revenueLeaderboard();
    }

    @GetMapping("/orders/{id}/invoice")
    public String invoice(@PathVariable("id") long id) {
        return "{\"formatted\":\"" + invoices.formatInvoiceDate(id) + "\"}";
    }

    @GetMapping("/orders/{id}/quote")
    public String quote(@PathVariable("id") long id) {
        return "{\"quote\":\"" + quotes.quoteFor(id).substring(0, 16) + "\"}";
    }
}
