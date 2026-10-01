package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps to the 'estimates' table (SRS section 8.2 High-Level Entities).
 */
@Getter
@Setter
@Entity
@Table(name = "estimates")
public class Estimate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_card_id")
    private JobCard jobCard;

    @Column(name = "subtotal")
    private BigDecimal subtotal;
    @Column(name = "discount")
    private BigDecimal discount;
    @Column(name = "tax")
    private BigDecimal tax;
    @Column(name = "total")
    private BigDecimal total;
    @Column(name = "status")
    private String status;

    @OneToMany(mappedBy = "estimate", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EstimateItem> items = new ArrayList<>();
}
