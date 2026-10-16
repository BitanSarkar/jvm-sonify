package net.bitsar.sonify.scenarios.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_line")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private CustomerOrder order;

    private String sku;

    private Integer quantity;

    @Column(name = "price_cents")
    private Long priceCents;

    private String description;

    protected OrderLine() {
    }

    public Long getId() {
        return id;
    }

    public CustomerOrder getOrder() {
        return order;
    }

    public String getSku() {
        return sku;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Long getPriceCents() {
        return priceCents;
    }

    public String getDescription() {
        return description;
    }
}
