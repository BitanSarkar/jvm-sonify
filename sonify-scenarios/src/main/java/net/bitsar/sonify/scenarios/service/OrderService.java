package net.bitsar.sonify.scenarios.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import net.bitsar.sonify.scenarios.domain.CustomerOrder;
import net.bitsar.sonify.scenarios.domain.OrderLine;
import net.bitsar.sonify.scenarios.repository.OrderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading orders, the ordinary way and the two ways it goes wrong. */
@Service
public class OrderService {

    private static final int PAGE_SIZE = 100;

    private final OrderRepository orders;

    @PersistenceContext
    private EntityManager entityManager;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * A listing page that renders each order's lines.
     *
     * <p>There is nothing unusual in this method, which is exactly why the bug survives review.
     * The repository call is one query; the loop that follows issues one more per order because
     * the association is lazy. A hundred rows on the page is a hundred and one queries.
     */
    @Transactional(readOnly = true)
    public int listOrdersLazily(String status) {
        List<CustomerOrder> page = orders.findByStatus(status, PageRequest.of(0, PAGE_SIZE)).getContent();
        int lines = 0;
        for (CustomerOrder order : page) {
            // Looks like a field access. Is a database round trip.
            for (OrderLine line : order.getLines()) {
                lines += line.getQuantity();
            }
        }
        return lines;
    }

    /**
     * The same page through a join fetch with pagination: the obvious fix, and a trap.
     *
     * <p>Hibernate cannot push the limit into SQL once a collection is joined, so it loads every
     * matching order and every line and then takes the first hundred in memory. Measured here
     * against forty thousand orders it is hundreds of times slower than the N+1 it replaces.
     */
    @Transactional(readOnly = true)
    public int listOrdersJoinFetchPaginated(String status) {
        List<CustomerOrder> page = orders.findByStatusJoinFetchPaginated(status, PageRequest.of(0, PAGE_SIZE));
        return countLines(page.size() > PAGE_SIZE ? page.subList(0, PAGE_SIZE) : page);
    }

    /**
     * The fix that works: one query for the page of identifiers, one for their lines.
     *
     * <p>The database applies the limit, because the first query has no join to multiply rows,
     * and the second is bounded by the identifiers it is given. Two queries for any page size.
     */
    @Transactional(readOnly = true)
    public int listOrdersBatched(String status) {
        List<Long> ids = orders.findIdsByStatus(status, PageRequest.of(0, PAGE_SIZE));
        if (ids.isEmpty()) {
            return 0;
        }
        return countLines(orders.findWithLinesByIds(ids));
    }

    private static int countLines(List<CustomerOrder> page) {
        int lines = 0;
        for (CustomerOrder order : page) {
            for (OrderLine line : order.getLines()) {
                lines += line.getQuantity();
            }
        }
        return lines;
    }

    /**
     * An export with no pagination.
     *
     * <p>Fine against a staging database with a few thousand rows, and a heap problem against
     * production. Hibernate holds every row it returns in the persistence context, so the cost
     * is the result set twice over.
     */
    @Transactional(readOnly = true)
    public int exportEverything(int limit) {
        List<CustomerOrder> everything = orders.findEverything();
        int counted = 0;
        for (CustomerOrder order : everything) {
            counted++;
            if (counted >= limit) {
                break;
            }
        }
        return counted;
    }

    /** A reporting query that genuinely takes a while, holding its connection throughout. */
    @Transactional(readOnly = true)
    public long revenueByCountry() {
        return entityManager.createQuery(
                        "select count(o) from CustomerOrder o join o.customer c "
                                + "where o.totalCents > :floor", Long.class)
                .setParameter("floor", 1000L)
                .getSingleResult();
    }
}
