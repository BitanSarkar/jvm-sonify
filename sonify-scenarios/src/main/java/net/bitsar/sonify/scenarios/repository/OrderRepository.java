package net.bitsar.sonify.scenarios.repository;

import java.util.List;
import net.bitsar.sonify.scenarios.domain.CustomerOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Three ways to read one page of orders with their lines, and only one of them is right.
 *
 * <p>The signatures give no hint of the difference. That is the whole problem.
 */
public interface OrderRepository extends JpaRepository<CustomerOrder, Long>,
        JpaSpecificationExecutor<CustomerOrder> {

    /** Lazy lines. Touching getLines() afterwards costs one query per order. */
    Page<CustomerOrder> findByStatus(String status, Pageable pageable);

    /**
     * A join fetch with pagination, which is the first thing anybody reaches for after being
     * told to fix an N+1.
     *
     * <p>It cannot work. A collection join multiplies rows, so the database cannot apply the
     * limit without losing lines, and Hibernate therefore fetches every matching row and
     * paginates in memory. Asking for a hundred orders loads forty thousand of them along with
     * two hundred thousand lines. It is dramatically worse than the problem it was meant to fix,
     * and it looks more correct.
     */
    @EntityGraph(attributePaths = "lines")
    @Query("select o from CustomerOrder o where o.status = :status")
    List<CustomerOrder> findByStatusJoinFetchPaginated(@Param("status") String status, Pageable pageable);

    /** Step one of the fix: the page of identifiers alone, which the database can limit. */
    @Query("select o.id from CustomerOrder o where o.status = :status order by o.id")
    List<Long> findIdsByStatus(@Param("status") String status, Pageable pageable);

    /** Step two: exactly those orders, with their lines, in a single further query. */
    @EntityGraph(attributePaths = "lines")
    @Query("select distinct o from CustomerOrder o where o.id in :ids")
    List<CustomerOrder> findWithLinesByIds(@Param("ids") List<Long> ids);

    @Query("select o from CustomerOrder o")
    List<CustomerOrder> findEverything();
}
