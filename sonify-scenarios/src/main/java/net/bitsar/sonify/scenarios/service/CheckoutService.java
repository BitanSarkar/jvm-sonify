package net.bitsar.sonify.scenarios.service;

import net.bitsar.sonify.scenarios.client.PaymentGatewayClient;
import net.bitsar.sonify.scenarios.domain.CustomerOrder;
import net.bitsar.sonify.scenarios.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Checkout, written the way it usually is written. */
@Service
public class CheckoutService {

    private final OrderRepository orders;
    private final PaymentGatewayClient payments;

    public CheckoutService(OrderRepository orders, PaymentGatewayClient payments) {
        this.orders = orders;
        this.payments = payments;
    }

    /**
     * Reads the order, calls the payment gateway, records the result.
     *
     * <p>The transaction is the problem, not the payment call. {@code @Transactional} holds a
     * pooled connection for the whole method, and the method spends almost all of its time
     * waiting on somebody else's HTTP endpoint. A pool of ten connections and a gateway taking
     * two seconds caps the entire application at five checkouts a second, however many threads
     * are free.
     *
     * <p>It is one of the most common ways a Spring application falls over, and it reviews
     * cleanly: every individual line is reasonable.
     */
    @Transactional
    public String checkoutInsideTransaction(long orderId) {
        CustomerOrder order = orders.findById(orderId).orElse(null);
        long amount = order == null ? 1000L : order.getTotalCents();
        String authorisation = payments.authorise(amount);
        return authorisation;
    }

    /** The same work with the gateway call moved outside the transaction. */
    public String checkoutOutsideTransaction(long orderId) {
        long amount = readAmount(orderId);
        return payments.authorise(amount);
    }

    @Transactional(readOnly = true)
    public long readAmount(long orderId) {
        return orders.findById(orderId).map(CustomerOrder::getTotalCents).orElse(1000L);
    }
}
