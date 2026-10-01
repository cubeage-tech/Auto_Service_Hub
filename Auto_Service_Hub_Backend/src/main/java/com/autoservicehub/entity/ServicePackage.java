package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps to the 'service_packages' table (SRS section 8.2 High-Level Entities).
 */
@Getter
@Setter
@Entity
@Table(name = "service_packages")
public class ServicePackage extends BaseEntity {

    @Column(name = "name")
    private String name;
    @Column(name = "code")
    private String code;
    @Column(name = "type")
    private String type;
    @Column(name = "price")
    private BigDecimal price;
    @Column(name = "duration_minutes")
    private Integer durationMinutes;
    @Column(name = "validity_days")
    private Integer validityDays;
    @Column(name = "gst_rate")
    private BigDecimal gstRate;
    @Column(name = "discount")
    private BigDecimal discount;
    @Column(name = "active")
    private Boolean active;

    @OneToMany(mappedBy = "servicePackage", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PackageItem> items = new ArrayList<>();
}
