package net.bitsar.sonify.scenarios.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An order and its lines.
 *
 * <p>The {@code lines} association is lazy, which is the JPA default and almost always the right
 * choice. It is also where the N+1 comes from: nothing about
 * {@code for (Order o : orders) o.getLines()} looks like it issues a query per row, and yet it
 * does. That is why the pathology is so common and so easy to miss in review.
 */
@Entity
@Table(name = "customer_order")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    private String status;

    @Column(name = "total_cents")
    private Long totalCents;

    @Column(name = "placed_at")
    private Instant placedAt;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<OrderLine> lines = new ArrayList<>();

    protected CustomerOrder() {
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getStatus() {
        return status;
    }

    public Long getTotalCents() {
        return totalCents;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public List<OrderLine> getLines() {
        return lines;
    }
}
